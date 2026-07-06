package com.barrier.riskengine.screening.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ScreeningResultJpaRepository extends JpaRepository<ScreeningResultEntity, UUID> {

  List<ScreeningResultEntity> findByAssessmentId(String assessmentId);
}
