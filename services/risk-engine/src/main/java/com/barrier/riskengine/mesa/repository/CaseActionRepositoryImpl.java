package com.barrier.riskengine.mesa.repository;

import com.barrier.riskengine.mesa.domain.CaseAction;
import com.barrier.riskengine.mesa.domain.CaseActionType;
import com.barrier.riskengine.mesa.repository.interfaces.CaseActionJpaRepository;
import com.barrier.riskengine.mesa.repository.interfaces.CaseActionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Implementação JPA da trilha de ações. Append-only. */
@Repository
class CaseActionRepositoryImpl implements CaseActionRepository {

  private final CaseActionJpaRepository jpa;

  CaseActionRepositoryImpl(CaseActionJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public CaseAction append(CaseAction action) {
    CaseActionEntity e = new CaseActionEntity();
    e.setId(action.id());
    e.setAssessmentId(action.assessmentId());
    e.setTenantId(action.tenantId());
    e.setActionType(action.type().name());
    e.setActor(action.actor());
    e.setDetail(action.detail());
    e.setOccurredAt(action.occurredAt());
    jpa.save(e);
    return action;
  }

  @Override
  public List<CaseAction> findByCase(UUID assessmentId, String tenantId) {
    return jpa.findByAssessmentIdAndTenantIdOrderByOccurredAtAsc(assessmentId, tenantId).stream()
        .map(CaseActionRepositoryImpl::toDomain)
        .toList();
  }

  private static CaseAction toDomain(CaseActionEntity e) {
    return new CaseAction(
        e.getId(),
        e.getAssessmentId(),
        e.getTenantId(),
        CaseActionType.valueOf(e.getActionType()),
        e.getActor(),
        e.getDetail(),
        e.getOccurredAt());
  }
}
