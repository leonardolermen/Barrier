package com.barrier.riskengine.subject.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TenantSubjectJpaRepository extends JpaRepository<TenantSubjectEntity, UUID> {

  Optional<TenantSubjectEntity> findByTenantIdAndSubjectId(String tenantId, UUID subjectId);

  boolean existsByTenantIdAndSubjectId(String tenantId, UUID subjectId);
}
