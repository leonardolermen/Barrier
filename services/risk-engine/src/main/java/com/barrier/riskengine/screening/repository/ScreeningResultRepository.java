package com.barrier.riskengine.screening.repository;

import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.util.List;

/** Repositório de domínio dos resultados de screening. */
public interface ScreeningResultRepository {

  ScreeningResult save(ScreeningResult result);

  List<ScreeningResult> findByAssessmentId(String assessmentId);
}
