package com.barrier.riskengine.riskpolicy.repository;

import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.PolicyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Mapeamento JPA de uma versão de política de risco do parceiro (migration V049). */
@Entity
@Table(name = "risk_policies")
@Getter(AccessLevel.PACKAGE)
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskPolicyEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "domain", nullable = false, length = 30)
  private PolicyDomain domain;

  @Column(name = "version", nullable = false)
  private int version;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private PolicyStatus status;

  @Column(name = "catalog_version", nullable = false)
  private int catalogVersion;

  /** A árvore de predicados inteira, no padrão de {@code partners_json}/{@code hits_json}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "rules_json", nullable = false)
  private String rulesJson;

  @Column(name = "created_by", nullable = false, length = 120)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "activated_by", length = 120)
  private String activatedBy;

  @Column(name = "activated_at")
  private Instant activatedAt;

  @Column(name = "archived_at")
  private Instant archivedAt;
}
