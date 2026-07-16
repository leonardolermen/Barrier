package com.barrier.riskengine.risk.registry.controller;

import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import java.time.Instant;

public record RiskRuleRegistryEntryResponse(
    String ruleCode,
    String description,
    String criticality,
    boolean enabled,
    Instant validFrom,
    Instant validUntil,
    Instant updatedAt) {

  static RiskRuleRegistryEntryResponse of(RiskRuleRegistryEntry entry) {
    return new RiskRuleRegistryEntryResponse(
        entry.ruleCode(),
        entry.description(),
        entry.criticality().name(),
        entry.enabled(),
        entry.validFrom(),
        entry.validUntil(),
        entry.updatedAt());
  }
}
