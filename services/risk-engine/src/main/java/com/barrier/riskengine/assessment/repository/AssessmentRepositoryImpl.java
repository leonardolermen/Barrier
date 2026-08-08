package com.barrier.riskengine.assessment.repository;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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

  @Override
  public Assessment save(Assessment assessment) {
    AssessmentEntity entity =
        jpa.findById(assessment.id().value()).orElseGet(AssessmentEntity::new);
    AssessmentEntityMapper.copyInto(assessment, entity);
    return AssessmentEntityMapper.toDomain(jpa.save(entity));
  }

  @Override
  public Optional<Assessment> findById(AssessmentId id) {
    return jpa.findById(id.value()).map(AssessmentEntityMapper::toDomain);
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
