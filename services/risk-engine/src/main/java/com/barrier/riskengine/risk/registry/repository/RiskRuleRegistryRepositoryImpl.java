package com.barrier.riskengine.risk.registry.repository;

import com.barrier.riskengine.policy.PolicyProvenance;
import com.barrier.riskengine.policy.RegistryPolicyState;
import com.barrier.riskengine.risk.registry.domain.RiskRuleCriticality;
import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.barrier.riskengine.risk.registry.repository.interfaces.RiskRuleRegistryJpaRepository;
import com.barrier.riskengine.risk.registry.repository.interfaces.RiskRuleRegistryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RiskRuleRegistryRepositoryImpl implements RiskRuleRegistryRepository {

  private static final String SELECT_HISTORY =
      "SELECT rule_code, enabled, criticality, description, valid_from, valid_until, updated_by,"
          + " changed_at FROM risk_rule_registry_history WHERE rule_code = ?";

  private static final String INSERT_HISTORY =
      "INSERT INTO risk_rule_registry_history"
          + " (id, rule_code, enabled, criticality, description, valid_from, valid_until,"
          + " updated_by, changed_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private final RiskRuleRegistryJpaRepository jpa;
  private final JdbcTemplate jdbc;

  RiskRuleRegistryRepositoryImpl(RiskRuleRegistryJpaRepository jpa, JdbcTemplate jdbc) {
    this.jpa = jpa;
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RiskRuleRegistryEntry> findByRuleCode(String ruleCode) {
    return jpa.findById(ruleCode).map(this::toDomain);
  }

  @Override
  public List<RiskRuleRegistryEntry> findAll() {
    return jpa.findAll().stream().map(this::toDomain).toList();
  }

  @Override
  public RiskRuleRegistryEntry upsert(
      String ruleCode,
      String description,
      String criticality,
      boolean enabled,
      Instant validFrom,
      Instant validUntil,
      String updatedBy) {
    Instant now = Instant.now();
    RiskRuleRegistryEntity entity =
        jpa.findById(ruleCode)
            .map(
                existing -> {
                  existing.update(
                      description, criticality, enabled, validFrom, validUntil, now, updatedBy);
                  return existing;
                })
            .orElseGet(
                () ->
                    new RiskRuleRegistryEntity(
                        ruleCode,
                        description,
                        criticality,
                        enabled,
                        validFrom,
                        validUntil,
                        now,
                        updatedBy));
    RiskRuleRegistryEntry saved = toDomain(jpa.save(entity));
    // Mesma transação da alteração: histórico gravado em transação separada pode faltar
    // exatamente quando a mudança aconteceu, que é quando ele importa.
    jdbc.update(
        INSERT_HISTORY,
        UUID.randomUUID(),
        ruleCode,
        enabled,
        criticality,
        description,
        validFrom == null ? null : java.sql.Timestamp.from(validFrom),
        validUntil == null ? null : java.sql.Timestamp.from(validUntil),
        updatedBy,
        java.sql.Timestamp.from(now));
    return saved;
  }

  @Override
  public Optional<RegistryPolicyState> historyAsOf(String ruleCode, Instant at) {
    if (at == null) {
      return Optional.empty();
    }
    return jdbc
        .query(
            SELECT_HISTORY + " AND changed_at <= ? ORDER BY changed_at DESC LIMIT 1",
            (rs, i) -> toState(rs, PolicyProvenance.FROM_HISTORY),
            ruleCode,
            java.sql.Timestamp.from(at))
        .stream()
        .findFirst();
  }

  @Override
  public boolean hasAnyHistory(String ruleCode) {
    Integer total =
        jdbc.queryForObject(
            "SELECT count(*) FROM risk_rule_registry_history WHERE rule_code = ?",
            Integer.class,
            ruleCode);
    return total != null && total > 0;
  }

  @Override
  public List<RegistryPolicyState> history(String ruleCode) {
    return jdbc.query(
        SELECT_HISTORY + " ORDER BY changed_at DESC",
        (rs, i) -> toState(rs, PolicyProvenance.FROM_HISTORY),
        ruleCode);
  }

  private static RegistryPolicyState toState(java.sql.ResultSet rs, PolicyProvenance provenance)
      throws java.sql.SQLException {
    return new RegistryPolicyState(
        rs.getString("rule_code"),
        rs.getBoolean("enabled"),
        rs.getString("criticality"),
        rs.getString("description"),
        instante(rs.getTimestamp("valid_from")),
        instante(rs.getTimestamp("valid_until")),
        rs.getString("updated_by"),
        instante(rs.getTimestamp("changed_at")),
        provenance);
  }

  private static Instant instante(java.sql.Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private RiskRuleRegistryEntry toDomain(RiskRuleRegistryEntity e) {
    return new RiskRuleRegistryEntry(
        e.getRuleCode(),
        e.getDescription(),
        RiskRuleCriticality.valueOf(e.getCriticality()),
        e.isEnabled(),
        e.getValidFrom(),
        e.getValidUntil(),
        e.getUpdatedAt());
  }
}
