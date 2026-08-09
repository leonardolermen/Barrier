package com.barrier.commons.outbox;

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
   * Reserva eventos pendentes para publicação exclusiva desta instância.
   *
   * <p>Sem {@code SKIP LOCKED}, todas as réplicas liam as mesmas linhas e publicavam o mesmo
   * evento no Kafka várias vezes. O consumidor de webhook deduplica por {@code eventId}, então
   * <i>este</i> caminho não gerava entrega duplicada — mas gerava tráfego multiplicado pelo número
   * de réplicas, e qualquer consumidor futuro que não deduplicasse herdaria o problema.
   *
   * <p>Diferente das avaliações, aqui não há lease: a publicação é curta e cabe dentro da mesma
   * transação que marca o evento como enviado, então o lock do banco basta.
   */
  @Query(
      value =
          """
          SELECT * FROM outbox
           WHERE status = 'PENDING'
           ORDER BY occurred_at
           LIMIT :limit
           FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<OutboxEvent> lockPending(@Param("limit") int limit);
}
