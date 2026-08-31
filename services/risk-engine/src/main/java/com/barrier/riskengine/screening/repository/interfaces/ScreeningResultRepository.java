package com.barrier.riskengine.screening.repository.interfaces;

import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repositório de domínio dos resultados de screening. */
public interface ScreeningResultRepository {

  ScreeningResult save(ScreeningResult result);

  List<ScreeningResult> findByAssessmentId(String assessmentId);

  /**
   * O screening <b>exato</b> apontado por {@code risk_scores.screening_result_id} (V028). Uma
   * avaliação retentada deixa várias linhas com o mesmo {@code assessment_id}, e só uma sustentou a
   * decisão gravada.
   */
  Optional<ScreeningResult> findById(UUID id);
}
