package com.barrier.riskengine.identity.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface IdentityCheckJpaRepository extends JpaRepository<IdentityCheckEntity, UUID> {

  List<IdentityCheckEntity> findByAssessmentId(String assessmentId);
}
