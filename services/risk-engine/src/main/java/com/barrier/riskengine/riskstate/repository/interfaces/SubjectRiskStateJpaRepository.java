package com.barrier.riskengine.riskstate.repository.interfaces;

import com.barrier.riskengine.riskstate.repository.SubjectRiskStateEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectRiskStateJpaRepository
    extends JpaRepository<SubjectRiskStateEntity, SubjectRiskStateEntity.Key> {

  Optional<SubjectRiskStateEntity> findBySubjectIdAndTenantId(UUID subjectId, String tenantId);
}
