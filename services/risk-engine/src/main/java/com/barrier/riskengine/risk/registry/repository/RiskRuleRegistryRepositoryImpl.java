package com.barrier.riskengine.risk.registry.repository;

import com.barrier.riskengine.risk.registry.domain.RiskRuleCriticality;
import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class RiskRuleRegistryRepositoryImpl implements RiskRuleRegistryRepository {

  private final RiskRuleRegistryJpaRepository jpa;

  RiskRuleRegistryRepositoryImpl(RiskRuleRegistryJpaRepository jpa) {
    this.jpa = jpa;
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
      Instant validUntil) {
    Instant now = Instant.now();
    RiskRuleRegistryEntity entity =
        jpa.findById(ruleCode)
            .map(
                existing -> {
                  existing.update(description, criticality, enabled, validFrom, validUntil, now);
                  return existing;
                })
            .orElseGet(
                () ->
                    new RiskRuleRegistryEntity(
                        ruleCode, description, criticality, enabled, validFrom, validUntil, now));
    return toDomain(jpa.save(entity));
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
