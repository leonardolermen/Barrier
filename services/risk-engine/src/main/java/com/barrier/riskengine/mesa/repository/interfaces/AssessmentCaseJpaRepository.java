package com.barrier.riskengine.mesa.repository.interfaces;

import com.barrier.riskengine.mesa.repository.AssessmentCaseEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentCaseJpaRepository extends JpaRepository<AssessmentCaseEntity, UUID> {

  Optional<AssessmentCaseEntity> findByAssessmentIdAndTenantId(UUID assessmentId, String tenantId);

  List<AssessmentCaseEntity> findByTenantIdAndQueueAndClosedAtIsNullOrderByOpenedAtAsc(
      String tenantId, String queue, Limit limit);
}
