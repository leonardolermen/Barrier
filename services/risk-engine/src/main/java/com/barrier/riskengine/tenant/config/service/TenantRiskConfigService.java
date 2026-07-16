package com.barrier.riskengine.tenant.config.service;

import java.util.Set;

/**
 * Lê overrides de config de risco por tenant, caindo no default global quando o tenant não tem
 * override para o parâmetro. As regras de risco chamam isto no lugar de usar {@code @Value}
 * diretamente para os parâmetros marcados como configuráveis por tenant.
 */
public interface TenantRiskConfigService {

  int getInt(String tenantId, String ruleCode, String paramKey, int defaultValue);

  /** Conjunto configurado pelo tenant unido ao default — nunca substitui, só acrescenta. */
  Set<String> getStringSet(
      String tenantId, String ruleCode, String paramKey, Set<String> defaultValue);
}
