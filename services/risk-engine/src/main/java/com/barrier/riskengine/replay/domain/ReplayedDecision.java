package com.barrier.riskengine.replay.domain;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;

/**
 * O que o motor de <b>hoje</b> conclui sobre a mesma evidência.
 *
 * <p>⚠️ Quando há lacuna de reconstrução, este agregado é calculado sobre um contexto incompleto e
 * <b>não</b> deve ser lido como "o motor decidiria isto": o veredito do replay será
 * {@link ReplayVerdict#DEGRADED} e as regras afetadas virão como
 * {@link RuleComparison#NOT_REPLAYABLE}. Ele é publicado assim mesmo porque omiti-lo esconderia o
 * que <i>foi</i> possível apurar — as regras de identidade e screening, que carregam os pesos
 * decisivos, continuam exatas.
 */
public record ReplayedDecision(
    RiskLevel level, int score, RiskRecommendation recommendation, String engineVersion) {}
