package com.barrier.riskengine.risk.registry.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Mapeamento JPA de uma linha do registry de regras de risco. */
@Entity
@Table(name = "risk_rule_registry")
class RiskRuleRegistryEntity {

  @Id
  @Column(name = "rule_code", nullable = false, length = 60)
  private String ruleCode;

  @Column(name = "description", nullable = false, length = 500)
  private String description;

  @Column(name = "criticality", nullable = false, length = 20)
  private String criticality;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "valid_from")
  private Instant validFrom;

  @Column(name = "valid_until")
  private Instant validUntil;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected RiskRuleRegistryEntity() {
    // JPA
  }

  RiskRuleRegistryEntity(
      String ruleCode,
      String description,
      String criticality,
      boolean enabled,
      Instant validFrom,
      Instant validUntil,
      Instant updatedAt) {
    this.ruleCode = ruleCode;
    this.description = description;
    this.criticality = criticality;
    this.enabled = enabled;
    this.validFrom = validFrom;
    this.validUntil = validUntil;
    this.updatedAt = updatedAt;
  }

  String getRuleCode() {
    return ruleCode;
  }

  String getDescription() {
    return description;
  }

  String getCriticality() {
    return criticality;
  }

  boolean isEnabled() {
    return enabled;
  }

  Instant getValidFrom() {
    return validFrom;
  }

  Instant getValidUntil() {
    return validUntil;
  }

  Instant getUpdatedAt() {
    return updatedAt;
  }

  void update(
      String description,
      String criticality,
      boolean enabled,
      Instant validFrom,
      Instant validUntil,
      Instant updatedAt) {
    this.description = description;
    this.criticality = criticality;
    this.enabled = enabled;
    this.validFrom = validFrom;
    this.validUntil = validUntil;
    this.updatedAt = updatedAt;
  }
}
