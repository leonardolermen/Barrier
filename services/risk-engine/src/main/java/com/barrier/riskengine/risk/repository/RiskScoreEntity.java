package com.barrier.riskengine.risk.repository;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento JPA da pontuação de risco; os resultados das regras ficam em JSON. */
@Entity
@Table(name = "risk_scores")
public class RiskScoreEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "assessment_id", nullable = false, length = 64)
  private String assessmentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "level", nullable = false, length = 10)
  private RiskLevel level;

  @Column(name = "score", nullable = false)
  private int totalScore;

  @Enumerated(EnumType.STRING)
  @Column(name = "recommendation", nullable = false, length = 10)
  private RiskRecommendation recommendation;

  /** JSONB: uma decisão com muitas regras disparadas estourava o teto (ver migration V026). */
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "results_json", nullable = false)
  private String resultsJson;

  /** Todas as regras avaliadas, com desfecho — inclusive as que passaram (ver migration V028). */
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "evaluated_json")
  private String evaluatedJson;

  @Column(name = "identity_check_id")
  private UUID identityCheckId;

  @Column(name = "screening_result_id")
  private UUID screeningResultId;

  @Column(name = "engine_version", nullable = false, length = 40)
  private String engineVersion;

  String getEvaluatedJson() {
    return evaluatedJson;
  }

  void setEvaluatedJson(String evaluatedJson) {
    this.evaluatedJson = evaluatedJson;
  }

  UUID getIdentityCheckId() {
    return identityCheckId;
  }

  void setIdentityCheckId(UUID identityCheckId) {
    this.identityCheckId = identityCheckId;
  }

  UUID getScreeningResultId() {
    return screeningResultId;
  }

  void setScreeningResultId(UUID screeningResultId) {
    this.screeningResultId = screeningResultId;
  }

  @Column(name = "scored_at", nullable = false)
  private Instant scoredAt;

  protected RiskScoreEntity() {
    // JPA
  }

  UUID getId() {
    return id;
  }

  void setId(UUID id) {
    this.id = id;
  }

  String getAssessmentId() {
    return assessmentId;
  }

  void setAssessmentId(String assessmentId) {
    this.assessmentId = assessmentId;
  }

  RiskLevel getLevel() {
    return level;
  }

  void setLevel(RiskLevel level) {
    this.level = level;
  }

  int getTotalScore() {
    return totalScore;
  }

  void setTotalScore(int totalScore) {
    this.totalScore = totalScore;
  }

  RiskRecommendation getRecommendation() {
    return recommendation;
  }

  void setRecommendation(RiskRecommendation recommendation) {
    this.recommendation = recommendation;
  }

  String getResultsJson() {
    return resultsJson;
  }

  void setResultsJson(String resultsJson) {
    this.resultsJson = resultsJson;
  }

  String getEngineVersion() {
    return engineVersion;
  }

  void setEngineVersion(String engineVersion) {
    this.engineVersion = engineVersion;
  }

  Instant getScoredAt() {
    return scoredAt;
  }

  void setScoredAt(Instant scoredAt) {
    this.scoredAt = scoredAt;
  }
}
