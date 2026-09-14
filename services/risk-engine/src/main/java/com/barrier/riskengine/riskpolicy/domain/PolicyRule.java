package com.barrier.riskengine.riskpolicy.domain;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;

/**
 * Uma regra escrita pelo parceiro. É deliberadamente a forma de um {@code RiskResult}: nada no
 * motor precisa aprender vocabulário novo.
 *
 * @param score pontos somados quando a condição casa. Nunca negativo — é a trava que preserva a
 *     monotonicidade de {@code ScoreAggregation} e, com ela, a garantia de que regra de parceiro
 *     não afrouxa decisão do motor
 * @param recommendation {@code null} quando a regra apenas pontua
 */
public record PolicyRule(
    String code,
    String name,
    Condition when,
    int score,
    Severity severity,
    RiskRecommendation recommendation) {}
