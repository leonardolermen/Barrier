package com.barrier.riskengine.risk.service;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.registry.service.RiskRuleRegistryService;
import com.barrier.riskengine.risk.repository.RiskScoreRepository;
import com.barrier.riskengine.risk.rule.RiskContext;
import com.barrier.riskengine.risk.rule.RiskRule;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Motor de risco: executa as regras ativas (Strategy), soma os scores em bandas de nível
 * (0–1000) e toma a recomendação mais severa entre a banda e os overrides das regras.
 * Persiste o score com os fatores e a versão do motor (explicabilidade + auditoria).
 *
 * <p>Bandas: ≤199 BAIXO · ≤499 MEDIO · ≤799 ALTO · &gt;799 CRITICO. ALTO/CRITICO sugerem
 * revisão/bloqueio; regras podem forçar overrides (sanção → REJECT; PEP → REVIEW).
 *
 * <p>Uma regra só é executada se {@link RiskRuleRegistryService#isActive(String)} disser que
 * está habilitada e dentro da vigência — ajuste operacional sem deploy, sobre o
 * {@link RiskRule#code()} (família), não sobre o {@code ruleCode} granular do resultado.
 *
 * <p>{@code ENGINE_VERSION} deve ser incrementado a cada mudança de regra ou peso, preservando
 * o histórico das decisões tomadas por versões anteriores.
 */
@Service
public class RiskScoringService {

  private static final Logger log = LoggerFactory.getLogger(RiskScoringService.class);

  static final String ENGINE_VERSION = "barrier-risk-rules/1.1.0";
  private static final int MAX_SCORE = 1000;

  private final List<RiskRule> rules;
  private final RiskScoreRepository repository;
  private final RiskRuleRegistryService registryService;

  public RiskScoringService(
      List<RiskRule> rules,
      RiskScoreRepository repository,
      RiskRuleRegistryService registryService) {
    this.rules = rules;
    this.repository = repository;
    this.registryService = registryService;
  }

  public RiskDecision score(RiskContext context) {
    List<RiskResult> triggered =
        rules.stream()
            .filter(this::activeOrLogSuppressed)
            .map(rule -> rule.evaluate(context))
            .filter(RiskResult::triggered)
            .toList();

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

  private boolean activeOrLogSuppressed(RiskRule rule) {
    boolean active = registryService.isActive(rule.code());
    if (!active) {
      log.debug("Regra {} suprimida pelo registry (desabilitada ou fora de vigência)", rule.code());
    }
    return active;
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
