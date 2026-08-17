package com.barrier.riskengine.mesa.repository.interfaces;

import com.barrier.riskengine.mesa.repository.CaseActionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseActionJpaRepository extends JpaRepository<CaseActionEntity, UUID> {

  List<CaseActionEntity> findByAssessmentIdAndTenantIdOrderByOccurredAtAsc(
      UUID assessmentId, String tenantId);
}
