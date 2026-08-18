package com.barrier.riskengine.riskstate.repository;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.riskstate.domain.SubjectRiskState;
import com.barrier.riskengine.riskstate.repository.interfaces.SubjectRiskStateJpaRepository;
import com.barrier.riskengine.riskstate.repository.interfaces.SubjectRiskStateRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Implementação JPA da projeção. Gravar reaproveita a linha existente da chave composta — é
 * projeção, e a única linha por (subject, tenant) é o ponto dela.
 */
@Repository
class SubjectRiskStateRepositoryImpl implements SubjectRiskStateRepository {

  private final SubjectRiskStateJpaRepository jpa;
  private final JdbcTemplate jdbc;

  SubjectRiskStateRepositoryImpl(SubjectRiskStateJpaRepository jpa, JdbcTemplate jdbc) {
    this.jpa = jpa;
    this.jdbc = jdbc;
  }

  @Override
  public java.util.List<SubjectRiskState> findDueForPeriodicReview(
      java.time.Duration menorIntervalo, int limit) {
    return jdbc.query(
        "SELECT * FROM subject_risk_state"
            + " WHERE evaluated_at < now() - (? * interval '1 second')"
            + " ORDER BY evaluated_at ASC LIMIT ?",
        (rs, row) ->
            new SubjectRiskState(
                rs.getObject("subject_id", java.util.UUID.class),
                rs.getString("tenant_id"),
                RiskLevel.valueOf(rs.getString("risk_level")),
                rs.getInt("risk_score"),
                AssessmentStatus.valueOf(rs.getString("decision")),
                rs.getObject("assessment_id", java.util.UUID.class),
                rs.getString("engine_version"),
                rs.getTimestamp("evaluated_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()),
        menorIntervalo.toSeconds(),
        limit);
  }

  @Override
  public Optional<SubjectRiskState> find(UUID subjectId, String tenantId) {
    return jpa.findBySubjectIdAndTenantId(subjectId, tenantId).map(SubjectRiskStateRepositoryImpl::toDomain);
  }

  @Override
  public SubjectRiskState save(SubjectRiskState state) {
    SubjectRiskStateEntity e =
        jpa.findBySubjectIdAndTenantId(state.subjectId(), state.tenantId())
            .orElseGet(SubjectRiskStateEntity::new);
    e.setSubjectId(state.subjectId());
    e.setTenantId(state.tenantId());
    e.setRiskLevel(state.level().name());
    e.setRiskScore(state.score());
    e.setDecision(state.decision().name());
    e.setAssessmentId(state.assessmentId());
    e.setEngineVersion(state.engineVersion());
    e.setEvaluatedAt(state.evaluatedAt());
    e.setUpdatedAt(state.updatedAt());
    return toDomain(jpa.save(e));
  }

  private static SubjectRiskState toDomain(SubjectRiskStateEntity e) {
    return new SubjectRiskState(
        e.getSubjectId(),
        e.getTenantId(),
        RiskLevel.valueOf(e.getRiskLevel()),
        e.getRiskScore(),
        AssessmentStatus.valueOf(e.getDecision()),
        e.getAssessmentId(),
        e.getEngineVersion(),
        e.getEvaluatedAt(),
        e.getUpdatedAt());
  }
}
