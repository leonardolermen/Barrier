package com.barrier.riskengine.risk.repository.interfaces;

import com.barrier.riskengine.risk.domain.model.RiskScore;
import java.util.List;

/** Repositório de domínio das pontuações de risco. */
public interface RiskScoreRepository {

  RiskScore save(RiskScore score);

  List<RiskScore> findByAssessmentId(String assessmentId);
}
