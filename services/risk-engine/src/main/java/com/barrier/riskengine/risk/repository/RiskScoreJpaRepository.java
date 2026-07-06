package com.barrier.riskengine.risk.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RiskScoreJpaRepository extends JpaRepository<RiskScoreEntity, UUID> {

  List<RiskScoreEntity> findByAssessmentId(String assessmentId);
}
