package com.barrier.riskengine.tenant.config.repository;

import com.barrier.riskengine.policy.ParamAuthorship;
import com.barrier.riskengine.policy.PolicyProvenance;
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

  @Override
  public Optional<ParamAuthorship> authorshipAsOf(
      String tenantId, String ruleCode, String paramKey, Instant at) {
    if (at == null) {
      return Optional.empty();
    }
    return jdbc
        .query(
            "SELECT param_key, param_value, updated_by, changed_at"
                + " FROM tenant_risk_config_history"
                + " WHERE tenant_id = ? AND rule_code = ? AND param_key = ? AND changed_at <= ?"
                + " ORDER BY changed_at DESC LIMIT 1",
            (rs, i) -> toAuthorship(rs),
            tenantId,
            ruleCode,
            paramKey,
            java.sql.Timestamp.from(at))
        .stream()
        .findFirst()
        // param_value nulo = override removido: naquele instante o valor vinha do default global,
        // e devolver a linha faria parecer que havia override zerado.
        .filter(a -> a.value() != null);
  }

  @Override
  public boolean hasAnyHistory(String tenantId, String ruleCode, String paramKey) {
    Integer total =
        jdbc.queryForObject(
            "SELECT count(*) FROM tenant_risk_config_history"
                + " WHERE tenant_id = ? AND rule_code = ? AND param_key = ?",
            Integer.class,
            tenantId,
            ruleCode,
            paramKey);
    return total != null && total > 0;
  }

  @Override
  public List<ParamAuthorship> history(String tenantId) {
    return jdbc.query(
        "SELECT rule_code, param_key, param_value, updated_by, changed_at"
            + " FROM tenant_risk_config_history WHERE tenant_id = ? ORDER BY changed_at DESC",
        (rs, i) ->
            new ParamAuthorship(
                rs.getString("rule_code") + ":" + rs.getString("param_key"),
                rs.getString("param_value") == null
                    ? ParamAuthorship.ParamSource.GLOBAL_DEFAULT
                    : ParamAuthorship.ParamSource.TENANT_OVERRIDE,
                rs.getString("param_value"),
                rs.getString("updated_by"),
                rs.getTimestamp("changed_at").toInstant(),
                PolicyProvenance.FROM_HISTORY),
        tenantId);
  }

  private static ParamAuthorship toAuthorship(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ParamAuthorship(
        rs.getString("param_key"),
        ParamAuthorship.ParamSource.TENANT_OVERRIDE,
        rs.getString("param_value"),
        rs.getString("updated_by"),
        rs.getTimestamp("changed_at").toInstant(),
        PolicyProvenance.FROM_HISTORY);
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
