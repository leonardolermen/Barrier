package com.barrier.riskengine.subject.profile.repository.interfaces;

import java.util.Optional;
import java.util.UUID;

import com.barrier.riskengine.subject.profile.repository.SubjectProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectProfileJpaRepository extends JpaRepository<SubjectProfileEntity, UUID> {

  Optional<SubjectProfileEntity> findBySubjectIdAndTenantId(UUID subjectId, String tenantId);
}
