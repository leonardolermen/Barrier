package com.barrier.riskengine.identity.repository;

import com.barrier.riskengine.identity.domain.IdentityStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento JPA de uma verificação de identidade. */
@Entity
@Table(name = "identity_checks")
class IdentityCheckEntity {

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

  Instant getCheckedAt() {
    return checkedAt;
  }

  void setCheckedAt(Instant checkedAt) {
    this.checkedAt = checkedAt;
  }
}
