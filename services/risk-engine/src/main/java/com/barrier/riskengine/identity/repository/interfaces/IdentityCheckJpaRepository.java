package com.barrier.riskengine.identity.repository.interfaces;

import java.util.List;
import java.util.UUID;

import com.barrier.riskengine.identity.repository.IdentityCheckEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityCheckJpaRepository extends JpaRepository<IdentityCheckEntity, UUID> {

  List<IdentityCheckEntity> findByAssessmentId(String assessmentId);
}
