package com.barrier.riskengine.tenant.config.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento JPA de um override de config de risco por tenant. */
@Entity
@Table(name = "tenant_risk_config")
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

  protected TenantRiskConfigEntity() {
    // JPA
  }

  TenantRiskConfigEntity(
      UUID id,
      String tenantId,
      String ruleCode,
      String paramKey,
      String paramValue,
      String updatedBy,
      Instant updatedAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.ruleCode = ruleCode;
    this.paramKey = paramKey;
    this.paramValue = paramValue;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  UUID getId() {
    return id;
  }

  String getTenantId() {
    return tenantId;
  }

  String getRuleCode() {
    return ruleCode;
  }

  String getParamKey() {
    return paramKey;
  }

  String getParamValue() {
    return paramValue;
  }

  String getUpdatedBy() {
    return updatedBy;
  }

  Instant getUpdatedAt() {
    return updatedAt;
  }

  void update(String paramValue, String updatedBy, Instant updatedAt) {
    this.paramValue = paramValue;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }
}
