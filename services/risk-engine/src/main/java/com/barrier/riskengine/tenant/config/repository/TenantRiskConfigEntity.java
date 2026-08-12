package com.barrier.riskengine.tenant.config.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapeamento JPA de um override de config de risco por tenant.
 *
 * <p>Sem {@code @Setter}: o estado muda por {@link #update}, não por setter solto.
 */
@Entity
@Table(name = "tenant_risk_config")
@Getter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
class TenantRiskConfigEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "rule_code", nullable = false, length = 60)
  private String ruleCode;

  @Column(name = "param_key", nullable = false, length = 60)
  private String paramKey;

  @Column(name = "param_value", nullable = false, length = 4000)
  private String paramValue;

  @Column(name = "updated_by", nullable = false, length = 120)
  private String updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  void update(String paramValue, String updatedBy, Instant updatedAt) {
    this.paramValue = paramValue;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }
}
