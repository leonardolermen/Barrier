package com.barrier.commons.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso à tabela de outbox. */
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

  /** Eventos pendentes, mais antigos primeiro. Usado em consultas/testes, não pelo relay. */
  List<OutboxEvent> findByStatusOrderByOccurredAtAsc(OutboxStatus status, Limit limit);

  /**
   * Linhas pendentes cuja posse está livre ou vencida, travadas para reivindicação.
   *
   * <p>{@code SKIP LOCKED} garante que réplicas concorrentes peguem conjuntos disjuntos; a lease
   * ({@code claimed_at}) é o que permite que a <b>publicação aconteça fora da transação</b>. Antes,
   * o lock do banco era mantido durante o {@code .join()} do Kafka, e a justificativa registrada
   * aqui — "a publicação é curta e cabe dentro da mesma transação" — só vale quando o broker está
   * saudável. Em rebalance ela deixa de valer, e o preço é uma conexão do pool presa com 100 linhas
   * travadas.
   *
   * <p>A ordem por {@code occurred_at} importa e não é cosmética: dois eventos da mesma avaliação
   * (conclusão automática e decisão manual) precisam chegar ao Kafka na ordem em que ocorreram,
   * porque a chave é o {@code assessmentId} e a ordenação da partição é o que o consumidor tem.
   */
  @Query(
      value =
          """
          SELECT * FROM outbox
           WHERE status = 'PENDING'
             AND (claimed_at IS NULL OR claimed_at < now() - (:leaseSeconds * interval '1 second'))
           ORDER BY occurred_at
           LIMIT :limit
           FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<OutboxEvent> selectClaimable(
      @Param("limit") int limit, @Param("leaseSeconds") long leaseSeconds);

  /**
   * Reivindica eventos pendentes para publicação por esta instância.
   *
   * <p>A marcação é por dirty checking: as entidades vêm gerenciadas da consulta acima e o
   * {@code claimed_at} é gravado no commit da transação que envolve esta chamada — que é curta e
   * <b>não</b> contém a publicação.
   */
  default List<OutboxEvent> claimPending(int limit, Duration lease) {
    List<OutboxEvent> claimable = selectClaimable(limit, lease.toSeconds());
    Instant now = Instant.now();
    claimable.forEach(event -> event.markClaimed(now));
    return claimable;
  }
}
