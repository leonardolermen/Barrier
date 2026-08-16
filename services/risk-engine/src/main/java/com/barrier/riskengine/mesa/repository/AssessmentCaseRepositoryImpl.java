package com.barrier.riskengine.mesa.repository;

import com.barrier.riskengine.mesa.domain.AssessmentCase;
import com.barrier.riskengine.mesa.domain.CaseQueue;
import com.barrier.riskengine.mesa.repository.interfaces.AssessmentCaseJpaRepository;
import com.barrier.riskengine.mesa.repository.interfaces.AssessmentCaseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/** Implementação JPA dos casos da mesa. */
@Repository
class AssessmentCaseRepositoryImpl implements AssessmentCaseRepository {

  private final AssessmentCaseJpaRepository jpa;

  AssessmentCaseRepositoryImpl(AssessmentCaseJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public AssessmentCase save(AssessmentCase caso) {
    AssessmentCaseEntity e =
        jpa.findByAssessmentIdAndTenantId(caso.assessmentId(), caso.tenantId())
            .orElseGet(AssessmentCaseEntity::new);
    e.setAssessmentId(caso.assessmentId());
    e.setTenantId(caso.tenantId());
    e.setQueue(caso.queue().name());
    e.setAssignedTo(caso.assignedTo());
    e.setOpenedAt(caso.openedAt());
    e.setClosedAt(caso.closedAt());
    return toDomain(jpa.save(e));
  }

  @Override
  public Optional<AssessmentCase> find(UUID assessmentId, String tenantId) {
    return jpa.findByAssessmentIdAndTenantId(assessmentId, tenantId).map(AssessmentCaseRepositoryImpl::toDomain);
  }

  @Override
  public List<AssessmentCase> findOpenByQueue(String tenantId, CaseQueue queue, int limit) {
    return jpa
        .findByTenantIdAndQueueAndClosedAtIsNullOrderByOpenedAtAsc(
            tenantId, queue.name(), Limit.of(limit))
        .stream()
        .map(AssessmentCaseRepositoryImpl::toDomain)
        .toList();
  }

  private static AssessmentCase toDomain(AssessmentCaseEntity e) {
    return new AssessmentCase(
        e.getAssessmentId(),
        e.getTenantId(),
        CaseQueue.valueOf(e.getQueue()),
        e.getAssignedTo(),
        e.getOpenedAt(),
        e.getClosedAt());
  }
}
