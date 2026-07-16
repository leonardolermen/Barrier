package com.barrier.riskengine.tenant.config.repository;

import com.barrier.riskengine.tenant.config.domain.TenantRiskConfigEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class TenantRiskConfigRepositoryImpl implements TenantRiskConfigRepository {

  private final TenantRiskConfigJpaRepository jpa;

  TenantRiskConfigRepositoryImpl(TenantRiskConfigJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<TenantRiskConfigEntry> find(String tenantId, String ruleCode, String paramKey) {
    return jpa.findByTenantIdAndRuleCodeAndParamKey(tenantId, ruleCode, paramKey).map(this::toDomain);
  }

  @Override
  public List<TenantRiskConfigEntry> findByTenant(String tenantId) {
    return jpa.findByTenantId(tenantId).stream().map(this::toDomain).toList();
  }

  @Override
  public TenantRiskConfigEntry upsert(
      String tenantId, String ruleCode, String paramKey, String paramValue, String updatedBy) {
    TenantRiskConfigEntity entity =
        jpa.findByTenantIdAndRuleCodeAndParamKey(tenantId, ruleCode, paramKey)
            .map(
                existing -> {
                  existing.update(paramValue, updatedBy, Instant.now());
                  return existing;
                })
            .orElseGet(
                () ->
                    new TenantRiskConfigEntity(
                        UUID.randomUUID(),
                        tenantId,
                        ruleCode,
                        paramKey,
                        paramValue,
                        updatedBy,
                        Instant.now()));
    return toDomain(jpa.save(entity));
  }

  private TenantRiskConfigEntry toDomain(TenantRiskConfigEntity e) {
    return new TenantRiskConfigEntry(
        e.getId(),
        e.getTenantId(),
        e.getRuleCode(),
        e.getParamKey(),
        e.getParamValue(),
        e.getUpdatedBy(),
        e.getUpdatedAt());
  }
}
