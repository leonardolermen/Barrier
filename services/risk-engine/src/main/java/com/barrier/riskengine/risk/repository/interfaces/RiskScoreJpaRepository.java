package com.barrier.riskengine.risk.repository.interfaces;

import java.util.List;
import java.util.UUID;

import com.barrier.riskengine.risk.repository.RiskScoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskScoreJpaRepository extends JpaRepository<RiskScoreEntity, UUID> {

  List<RiskScoreEntity> findByAssessmentId(String assessmentId);
}
