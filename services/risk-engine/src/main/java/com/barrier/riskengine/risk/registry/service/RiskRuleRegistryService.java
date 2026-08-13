package com.barrier.riskengine.risk.registry.service;

import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import java.util.List;

/**
 * Consulta e gestão do registry de regras: permite habilitar/desabilitar uma família de regra
 * inteira e limitar sua vigência sem deploy. Regra sem linha no registry é considerada ativa
 * por padrão (fail-open — o registry é um kill switch operacional, não uma allowlist).
 */
public interface RiskRuleRegistryService {

  boolean isActive(String ruleCode);

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
