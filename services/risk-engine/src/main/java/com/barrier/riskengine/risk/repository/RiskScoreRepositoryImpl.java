package com.barrier.riskengine.risk.repository;

import com.barrier.riskengine.risk.domain.model.EvaluatedRule;
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
  private static final TypeReference<List<EvaluatedRule>> EVALUATED_LIST = new TypeReference<>() {};

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
    e.setEvaluatedJson(objectMapper.writeValueAsString(score.evaluated()));
    e.setIdentityCheckId(score.identityCheckId());
    e.setScreeningResultId(score.screeningResultId());
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
    // Decisão anterior à V028 não tem a trilha completa — lista vazia, não erro: o registro
    // histórico continua legível, que é o ponto de não ter mudado results_json de forma.
    List<EvaluatedRule> evaluated =
        e.getEvaluatedJson() == null
            ? List.of()
            : objectMapper.readValue(e.getEvaluatedJson(), EVALUATED_LIST);
    return new RiskScore(
        e.getId(),
        e.getAssessmentId(),
        e.getLevel(),
        e.getTotalScore(),
        e.getRecommendation(),
        results,
        evaluated,
        e.getIdentityCheckId(),
        e.getScreeningResultId(),
        e.getEngineVersion(),
        e.getScoredAt());
  }
}
