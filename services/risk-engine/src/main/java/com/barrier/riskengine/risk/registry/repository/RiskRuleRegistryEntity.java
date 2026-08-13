package com.barrier.riskengine.risk.registry.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapeamento JPA de uma linha do registry de regras de risco.
 *
 * <p>Sem {@code @Setter}: o estado muda por {@link #update}, não por setter solto.
 */
@Entity
@Table(name = "risk_rule_registry")
@Getter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class RiskRuleRegistryEntity {

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

  /** Quem fez a última alteração; o histórico guarda as anteriores (V033). */
  @Column(name = "updated_by", length = 120)
  private String updatedBy;

  void update(
      String description,
      String criticality,
      boolean enabled,
      Instant validFrom,
      Instant validUntil,
      Instant updatedAt,
      String updatedBy) {
    this.updatedBy = updatedBy;
    this.description = description;
    this.criticality = criticality;
    this.enabled = enabled;
    this.validFrom = validFrom;
    this.validUntil = validUntil;
    this.updatedAt = updatedAt;
  }
}
