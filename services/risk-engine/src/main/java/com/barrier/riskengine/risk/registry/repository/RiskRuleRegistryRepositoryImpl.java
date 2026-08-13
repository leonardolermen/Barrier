package com.barrier.riskengine.risk.registry.repository;

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
