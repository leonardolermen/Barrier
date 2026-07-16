package com.barrier.riskengine.risk.registry.domain;

import java.time.Instant;

/**
 * Estado operacional de uma família de {@code RiskRule} ({@code RiskRule.code()}): pode ser
 * desabilitada ou ter vigência limitada sem deploy — ajuste de compliance/operação, não
 * override de parâmetro por parceiro (isso é {@code TenantRiskConfigEntry}).
 */
public record RiskRuleRegistryEntry(
    String ruleCode,
    String description,
    RiskRuleCriticality criticality,
    boolean enabled,
    Instant validFrom,
    Instant validUntil,
    Instant updatedAt) {

  /** Ativa agora: habilitada e dentro da janela de vigência (limites nulos = sem limite). */
  public boolean activeAt(Instant instant) {
    if (!enabled) {
      return false;
    }
    if (validFrom != null && instant.isBefore(validFrom)) {
      return false;
    }
    return validUntil == null || !instant.isAfter(validUntil);
  }
}
