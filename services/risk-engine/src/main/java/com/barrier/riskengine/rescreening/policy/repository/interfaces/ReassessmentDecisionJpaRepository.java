package com.barrier.riskengine.rescreening.policy.repository.interfaces;

import com.barrier.riskengine.rescreening.policy.repository.ReassessmentDecisionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReassessmentDecisionJpaRepository
    extends JpaRepository<ReassessmentDecisionEntity, UUID> {

  List<ReassessmentDecisionEntity> findBySubjectIdAndTenantIdOrderByDecidedAtDesc(
      UUID subjectId, String tenantId, Limit limit);
}
