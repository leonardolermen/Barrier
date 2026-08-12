package com.barrier.riskengine.assessment.repository;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Implementação JPA do repositório de domínio. */
@Repository
class AssessmentRepositoryImpl implements AssessmentRepository {

  /**
   * Reivindica avaliações pendentes para processamento exclusivo.
   *
   * <p>{@code FOR UPDATE SKIP LOCKED} é o que torna a escala horizontal correta: cada réplica
   * pega um conjunto disjunto em vez de todas lerem as mesmas linhas. Sem isso, duas instâncias
   * processavam a mesma avaliação, consumiam o bureau duas vezes e emitiam dois eventos — com
   * {@code eventId} diferentes, então a idempotência do webhook não filtrava, e o cliente recebia
   * dois callbacks possivelmente contraditórios.
   *
   * <p>{@code claimed_at} é lease com expiração: se a instância morrer no meio, a linha volta a
   * ser reivindicável sozinha. {@code SKIP LOCKED} sem lease resolveria a concorrência só dentro
   * de uma transação — e a transação não pode durar o processamento inteiro, porque ele faz
   * chamadas HTTP.
   */
  private static final String SELECT_CLAIMABLE =
      """
      SELECT id FROM assessments
       WHERE status = 'EM_ANALISE'
         AND (next_attempt_at IS NULL OR next_attempt_at <= now())
         AND (claimed_at IS NULL OR claimed_at < now() - (? * interval '1 second'))
       ORDER BY created_at
       LIMIT ?
       FOR UPDATE SKIP LOCKED
      """;

  private final AssessmentJpaRepository jpa;
  private final JdbcTemplate jdbc;

  AssessmentRepositoryImpl(AssessmentJpaRepository jpa, JdbcTemplate jdbc) {
    this.jpa = jpa;
    this.jdbc = jdbc;
  }

  /**
   * Grava o agregado, recusando a escrita se a linha mudou desde que ele foi carregado.
   *
   * <p>A coluna {@code version} existe desde V023 justamente para isto, mas não protegia nada:
   * este método <b>relia</b> a entidade imediatamente antes de gravar, então o {@code @Version} era
   * comparado com um valor obtido microssegundos antes — sempre igual, por construção. O agregado,
   * carregado minutos antes (o processamento faz chamadas de bureau no intervalo), não carregava
   * versão alguma.
   *
   * <p>O efeito prático era que a lease expirada — GC longo, container congelado, bureau lento —
   * fazia outra réplica reivindicar a mesma avaliação, e as duas concluíam: cada uma sobre a própria
   * cópia em memória, ambas passando pelo guard de estado (as duas estavam {@code EM_ANALISE}
   * quando lidas), ambas gravando um evento com {@code eventId} distinto. A idempotência do webhook
   * é por {@code eventId} e não filtrava: o cliente recebia dois callbacks, possivelmente com
   * decisões diferentes. Era exatamente o cenário que V023 declarava resolvido.
   *
   * <p>Agora a linha é lida com {@code FOR UPDATE} e a versão é comparada com a que o agregado
   * trouxe. Perdedor da corrida recebe {@link OptimisticLockingFailureException} e sua transação —
   * incluindo o evento na outbox — é desfeita.
   */
  @Override
  public Assessment save(Assessment assessment) {
    AssessmentEntity entity =
        jpa.findWithLockById(assessment.id().value()).orElseGet(AssessmentEntity::new);
    if (entity.getId() != null && entity.getVersion() != assessment.version()) {
      throw new OptimisticLockingFailureException(
          "Avaliação "
              + assessment.id().asString()
              + " foi alterada por outro processo (versão esperada "
              + assessment.version()
              + ", encontrada "
              + entity.getVersion()
              + ")");
    }
    AssessmentEntityMapper.copyInto(assessment, entity);
    return AssessmentEntityMapper.toDomain(jpa.save(entity));
  }

  @Override
  public Optional<Assessment> findById(AssessmentId id) {
    return jpa.findById(id.value()).map(AssessmentEntityMapper::toDomain);
  }

  @Override
  public long countPending() {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM assessments WHERE status = 'EM_ANALISE'", Long.class);
    return count == null ? 0 : count;
  }

  @Override
  public Instant oldestPendingCreatedAt() {
    return jdbc.queryForObject(
        "SELECT min(created_at) FROM assessments WHERE status = 'EM_ANALISE'", Instant.class);
  }

  @Override
  @Transactional
  public List<AssessmentId> claimPending(int limit, Duration lease) {
    List<UUID> ids = jdbc.queryForList(SELECT_CLAIMABLE, UUID.class, lease.toSeconds(), limit);
    if (ids.isEmpty()) {
      return List.of();
    }
    // O UPDATE roda na mesma transação do SELECT ... FOR UPDATE: os locks seguem valendo, então
    // nenhuma outra réplica consegue reivindicar estas linhas no intervalo.
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    jdbc.update(
        "UPDATE assessments SET claimed_at = now() WHERE id IN (" + placeholders + ")",
        ids.toArray());
    return ids.stream().map(AssessmentId::new).toList();
  }
}
