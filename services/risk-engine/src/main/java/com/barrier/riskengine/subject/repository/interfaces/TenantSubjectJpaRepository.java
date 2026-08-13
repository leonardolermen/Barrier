package com.barrier.riskengine.subject.repository.interfaces;

import java.util.Optional;
import java.util.UUID;

import com.barrier.riskengine.subject.repository.TenantSubjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantSubjectJpaRepository extends JpaRepository<TenantSubjectEntity, UUID> {

  Optional<TenantSubjectEntity> findByTenantIdAndSubjectId(String tenantId, UUID subjectId);

  boolean existsByTenantIdAndSubjectId(String tenantId, UUID subjectId);
}
