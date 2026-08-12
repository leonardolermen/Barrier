package com.barrier.riskengine.identity.repository;

import com.barrier.riskengine.identity.domain.IdentityStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento JPA de uma verificação de identidade. */
@Entity
@Table(name = "identity_checks")
public class IdentityCheckEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "assessment_id", nullable = false, length = 64)
  private String assessmentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private IdentityStatus status;

  @Column(name = "provider", nullable = false, length = 60)
  private String provider;

  @Column(name = "detail", length = 400)
  private String detail;

  @Column(name = "checked_at", nullable = false)
  private Instant checkedAt;

  /** Id da consulta no provedor (QueryId); nulo quando a fonte não fornece. Ver V031. */
  @Column(name = "provider_reference", length = 120)
  private String providerReference;

  /** Resposta do bureau, com redação dos campos sensíveis. JSONB — ver V031. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "raw_response", columnDefinition = "jsonb")
  private String rawResponse;

  protected IdentityCheckEntity() {
    // JPA
  }

  UUID getId() {
    return id;
  }

  void setId(UUID id) {
    this.id = id;
  }

  String getAssessmentId() {
    return assessmentId;
  }

  void setAssessmentId(String assessmentId) {
    this.assessmentId = assessmentId;
  }

  IdentityStatus getStatus() {
    return status;
  }

  void setStatus(IdentityStatus status) {
    this.status = status;
  }

  String getProvider() {
    return provider;
  }

  void setProvider(String provider) {
    this.provider = provider;
  }

  String getDetail() {
    return detail;
  }

  void setDetail(String detail) {
    this.detail = detail;
  }

  String getProviderReference() {
    return providerReference;
  }

  void setProviderReference(String providerReference) {
    this.providerReference = providerReference;
  }

  String getRawResponse() {
    return rawResponse;
  }

  void setRawResponse(String rawResponse) {
    this.rawResponse = rawResponse;
  }

  Instant getCheckedAt() {
    return checkedAt;
  }

  void setCheckedAt(Instant checkedAt) {
    this.checkedAt = checkedAt;
  }
}
