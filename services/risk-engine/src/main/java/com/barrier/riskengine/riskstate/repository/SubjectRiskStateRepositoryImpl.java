package com.barrier.riskengine.riskstate.repository;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.riskstate.domain.SubjectRiskState;
import com.barrier.riskengine.riskstate.repository.interfaces.SubjectRiskStateJpaRepository;
import com.barrier.riskengine.riskstate.repository.interfaces.SubjectRiskStateRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implementação JPA da projeção. Gravar reaproveita a linha existente da chave composta — é
 * projeção, e a única linha por (subject, tenant) é o ponto dela.
 */
@Repository
class SubjectRiskStateRepositoryImpl implements SubjectRiskStateRepository {

  private final SubjectRiskStateJpaRepository jpa;

  SubjectRiskStateRepositoryImpl(SubjectRiskStateJpaRepository jpa) {
    this.jpa = jpa;
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
