package com.barrier.riskengine.tenant.config.repository;

import com.barrier.riskengine.tenant.config.domain.TenantRiskConfigEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class TenantRiskConfigRepositoryImpl implements TenantRiskConfigRepository {

  private static final String INSERT_HISTORY =
      "INSERT INTO tenant_risk_config_history"
          + " (id, tenant_id, rule_code, param_key, param_value, updated_by, changed_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?)";

  private final TenantRiskConfigJpaRepository jpa;
  private final JdbcTemplate jdbc;

  TenantRiskConfigRepositoryImpl(TenantRiskConfigJpaRepository jpa, JdbcTemplate jdbc) {
    this.jpa = jpa;
    this.jdbc = jdbc;
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
    TenantRiskConfigEntry saved = toDomain(jpa.save(entity));
    // Mesma transação da alteração: histórico gravado à parte pode faltar exatamente quando a
    // mudança aconteceu, que é quando ele importa.
    jdbc.update(
        INSERT_HISTORY,
        UUID.randomUUID(),
        tenantId,
        ruleCode,
        paramKey,
        paramValue,
        updatedBy,
        java.sql.Timestamp.from(saved.updatedAt()));
    return saved;
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
