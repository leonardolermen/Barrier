package com.barrier.riskengine.tenant.config.controller;

import com.barrier.riskengine.tenant.config.domain.TenantRiskConfigEntry;
import java.time.Instant;

/** Um parâmetro efetivo (override do tenant, ou default global quando não há override). */
public record RiskConfigEntryResponse(
    String ruleCode,
    String paramKey,
    String value,
    boolean overridden,
    String updatedBy,
    Instant updatedAt) {

  static RiskConfigEntryResponse override(TenantRiskConfigEntry entry) {
    return new RiskConfigEntryResponse(
        entry.ruleCode(), entry.paramKey(), entry.paramValue(), true, entry.updatedBy(),
        entry.updatedAt());
  }

  static RiskConfigEntryResponse fromDefault(String ruleCode, String paramKey, String value) {
    return new RiskConfigEntryResponse(ruleCode, paramKey, value, false, null, null);
  }
}
