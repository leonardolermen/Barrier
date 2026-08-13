package com.barrier.riskengine.risk.registry.repository.interfaces;

import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import java.util.List;
import java.util.Optional;

/** Repositório de domínio do registry de regras de risco. */
public interface RiskRuleRegistryRepository {

  Optional<RiskRuleRegistryEntry> findByRuleCode(String ruleCode);

  List<RiskRuleRegistryEntry> findAll();

  RiskRuleRegistryEntry upsert(
      String ruleCode,
      String description,
      String criticality,
      boolean enabled,
      java.time.Instant validFrom,
      java.time.Instant validUntil,
      String updatedBy);
}
