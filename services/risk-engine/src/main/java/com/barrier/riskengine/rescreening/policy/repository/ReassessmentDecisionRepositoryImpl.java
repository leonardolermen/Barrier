package com.barrier.riskengine.rescreening.policy.repository;

import com.barrier.riskengine.rescreening.policy.domain.ReassessmentDecision;
import com.barrier.riskengine.rescreening.policy.domain.ReassessmentTrigger;
import com.barrier.riskengine.rescreening.policy.repository.interfaces.ReassessmentDecisionJpaRepository;
import com.barrier.riskengine.rescreening.policy.repository.interfaces.ReassessmentDecisionRepository;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/** Implementação JPA da trilha de reavaliação. */
@Repository
class ReassessmentDecisionRepositoryImpl implements ReassessmentDecisionRepository {

  private final ReassessmentDecisionJpaRepository jpa;

  ReassessmentDecisionRepositoryImpl(ReassessmentDecisionJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public ReassessmentDecision save(ReassessmentDecision decision) {
    ReassessmentDecisionEntity e = new ReassessmentDecisionEntity();
    e.setId(decision.id());
    e.setSubjectId(decision.subjectId());
    e.setTenantId(decision.tenantId());
    e.setTriggerType(decision.trigger().name());
    e.setTriggerDetail(decision.triggerDetail());
    e.setReassessed(decision.reassess());
    e.setReason(decision.reason());
    e.setRiskLevel(decision.riskLevel() == null ? null : decision.riskLevel().name());
    e.setDecidedAt(decision.decidedAt());
    jpa.save(e);
    return decision;
  }

  @Override
  public List<ReassessmentDecision> findBySubject(UUID subjectId, String tenantId, int limit) {
    return jpa
        .findBySubjectIdAndTenantIdOrderByDecidedAtDesc(subjectId, tenantId, Limit.of(limit))
        .stream()
        .map(ReassessmentDecisionRepositoryImpl::toDomain)
        .toList();
  }

  private static ReassessmentDecision toDomain(ReassessmentDecisionEntity e) {
    return new ReassessmentDecision(
        e.getId(),
        e.getSubjectId(),
        e.getTenantId(),
        ReassessmentTrigger.valueOf(e.getTriggerType()),
        e.getTriggerDetail(),
        e.isReassessed(),
        e.getReason(),
        e.getRiskLevel() == null ? null : RiskLevel.valueOf(e.getRiskLevel()),
        e.getDecidedAt());
  }
}
