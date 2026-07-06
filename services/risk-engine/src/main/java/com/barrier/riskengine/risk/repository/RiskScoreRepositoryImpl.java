package com.barrier.riskengine.risk.repository;

import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import java.util.List;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Implementação JPA do repositório de risco; os resultados vão para {@code results_json}. */
@Repository
class RiskScoreRepositoryImpl implements RiskScoreRepository {

  private static final TypeReference<List<RiskResult>> RESULT_LIST = new TypeReference<>() {};

  private final RiskScoreJpaRepository jpa;
  private final ObjectMapper objectMapper;

  RiskScoreRepositoryImpl(RiskScoreJpaRepository jpa, ObjectMapper objectMapper) {
    this.jpa = jpa;
    this.objectMapper = objectMapper;
  }

  @Override
  public RiskScore save(RiskScore score) {
    RiskScoreEntity e = new RiskScoreEntity();
    e.setId(score.id());
    e.setAssessmentId(score.assessmentId());
    e.setLevel(score.level());
    e.setTotalScore(score.totalScore());
    e.setRecommendation(score.recommendation());
    e.setResultsJson(objectMapper.writeValueAsString(score.results()));
    e.setEngineVersion(score.engineVersion());
    e.setScoredAt(score.scoredAt());
    return toDomain(jpa.save(e));
  }

  @Override
  public List<RiskScore> findByAssessmentId(String assessmentId) {
    return jpa.findByAssessmentId(assessmentId).stream().map(this::toDomain).toList();
  }

  private RiskScore toDomain(RiskScoreEntity e) {
    List<RiskResult> results = objectMapper.readValue(e.getResultsJson(), RESULT_LIST);
    return new RiskScore(
        e.getId(),
        e.getAssessmentId(),
        e.getLevel(),
        e.getTotalScore(),
        e.getRecommendation(),
        results,
        e.getEngineVersion(),
        e.getScoredAt());
  }
}
