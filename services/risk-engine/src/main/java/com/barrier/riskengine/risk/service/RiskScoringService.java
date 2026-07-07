package com.barrier.riskengine.risk.service;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.repository.RiskScoreRepository;
import com.barrier.riskengine.risk.rule.RiskContext;
import com.barrier.riskengine.risk.rule.RiskRule;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Motor de risco: executa todas as regras (Strategy), soma os scores em bandas de nível
 * (0–1000) e toma a recomendação mais severa entre a banda e os overrides das regras.
 * Persiste o score com os fatores e a versão do motor (explicabilidade + auditoria).
 *
 * <p>Bandas: ≤199 BAIXO · ≤499 MEDIO · ≤799 ALTO · &gt;799 CRITICO. ALTO/CRITICO sugerem
 * revisão/bloqueio; regras podem forçar overrides (sanção → REJECT; PEP → REVIEW).
 *
 * <p>{@code ENGINE_VERSION} deve ser incrementado a cada mudança de regra ou peso, preservando
 * o histórico das decisões tomadas por versões anteriores.
 */
@Service
public class RiskScoringService {

  static final String ENGINE_VERSION = "barrier-risk-rules/1.0.0";
  private static final int MAX_SCORE = 1000;

  private final List<RiskRule> rules;
  private final RiskScoreRepository repository;

  public RiskScoringService(List<RiskRule> rules, RiskScoreRepository repository) {
    this.rules = rules;
    this.repository = repository;
  }

  public RiskDecision score(RiskContext context) {
    List<RiskResult> triggered =
        rules.stream().map(rule -> rule.evaluate(context)).filter(RiskResult::triggered).toList();

    int total = Math.min(MAX_SCORE, triggered.stream().mapToInt(RiskResult::score).sum());
    RiskLevel level = band(total);

    RiskRecommendation recommendation =
        triggered.stream()
            .map(RiskResult::recommendation)
            .filter(Objects::nonNull)
            .reduce(fromLevel(level), RiskRecommendation::strongest);

    RiskDecision decision =
        new RiskDecision(level, recommendation, total, triggered, ENGINE_VERSION);
    repository.save(RiskScore.from(context.assessmentId(), decision));
    return decision;
  }

  private static RiskLevel band(int score) {
    if (score <= 199) {
      return RiskLevel.LOW;
    }
    if (score <= 499) {
      return RiskLevel.MEDIUM;
    }
    return score <= 799 ? RiskLevel.HIGH : RiskLevel.CRITICAL;
  }

  private static RiskRecommendation fromLevel(RiskLevel level) {
    return switch (level) {
      case LOW, MEDIUM -> RiskRecommendation.APPROVE;
      case HIGH -> RiskRecommendation.REVIEW;
      case CRITICAL -> RiskRecommendation.REJECT;
    };
  }
}
