package com.barrier.riskengine.risk.rule.interfaces;

import java.util.List;

/**
 * Regras vindas da política do parceiro para uma avaliação, e a versão que as produziu.
 *
 * @param policyVersion versão da política ativa; {@code null} quando o tenant não tem nenhuma
 * @param rules regras já compiladas e prontas para entrar no motor
 */
public record CustomRules(Integer policyVersion, List<RiskRule> rules) {

  /** Nenhum parceiro escreveu política, ou a fonte não está disponível. */
  public static final CustomRules NONE = new CustomRules(null, List.of());

  public CustomRules {
    rules = List.copyOf(rules);
  }
}
