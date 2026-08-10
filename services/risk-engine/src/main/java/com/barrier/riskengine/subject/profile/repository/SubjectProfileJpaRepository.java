package com.barrier.riskengine.subject.profile.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SubjectProfileJpaRepository extends JpaRepository<SubjectProfileEntity, UUID> {

  Optional<SubjectProfileEntity> findBySubjectIdAndTenantId(UUID subjectId, String tenantId);
}
