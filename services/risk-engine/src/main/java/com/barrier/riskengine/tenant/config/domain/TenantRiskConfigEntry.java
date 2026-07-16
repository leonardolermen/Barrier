package com.barrier.riskengine.tenant.config.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Override de um parâmetro de regra de risco para um tenant específico. Ausência de registro
 * para um {@code (tenantId, ruleCode, paramKey)} significa "usa o default global".
 */
public record TenantRiskConfigEntry(
    UUID id,
    String tenantId,
    String ruleCode,
    String paramKey,
    String paramValue,
    String updatedBy,
    Instant updatedAt) {

  public static TenantRiskConfigEntry create(
      String tenantId, String ruleCode, String paramKey, String paramValue, String updatedBy) {
    return new TenantRiskConfigEntry(
        UUID.randomUUID(), tenantId, ruleCode, paramKey, paramValue, updatedBy, Instant.now());
  }
}
