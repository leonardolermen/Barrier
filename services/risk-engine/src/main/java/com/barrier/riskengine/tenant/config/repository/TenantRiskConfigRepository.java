package com.barrier.riskengine.tenant.config.repository;

import com.barrier.riskengine.tenant.config.domain.TenantRiskConfigEntry;
import java.util.List;
import java.util.Optional;

/** Repositório de domínio dos overrides de config de risco por tenant. */
public interface TenantRiskConfigRepository {

  Optional<TenantRiskConfigEntry> find(String tenantId, String ruleCode, String paramKey);

  List<TenantRiskConfigEntry> findByTenant(String tenantId);

  /** Cria o override se não existir, ou atualiza o valor/autor de um já existente. */
  TenantRiskConfigEntry upsert(
      String tenantId, String ruleCode, String paramKey, String paramValue, String updatedBy);
}
