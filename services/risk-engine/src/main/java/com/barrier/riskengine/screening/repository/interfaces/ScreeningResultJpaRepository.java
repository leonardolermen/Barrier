package com.barrier.riskengine.screening.repository.interfaces;

import java.util.List;
import java.util.UUID;

import com.barrier.riskengine.screening.repository.ScreeningResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScreeningResultJpaRepository extends JpaRepository<ScreeningResultEntity, UUID> {

  List<ScreeningResultEntity> findByAssessmentId(String assessmentId);
}
