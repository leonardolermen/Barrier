package com.barrier.riskengine.screening.repository;

import com.barrier.riskengine.screening.domain.ScreeningStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento JPA do resultado de screening; os apontamentos ficam serializados em JSON. */
@Entity
@Table(name = "screening_results")
class ScreeningResultEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "assessment_id", nullable = false, length = 64)
  private String assessmentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ScreeningStatus status;

  /** JSONB: um cliente com muitos apontamentos estourava o teto (ver migration V026). */
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "hits_json", nullable = false)
  private String hitsJson;

  /** Fonte → versão da lista consultada; snapshot que torna o CLEAR verificável (V028). */
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "sources_json")
  private String sourcesJson;

  String getSourcesJson() {
    return sourcesJson;
  }

  void setSourcesJson(String sourcesJson) {
    this.sourcesJson = sourcesJson;
  }

  @Column(name = "checked_at", nullable = false)
  private Instant checkedAt;

  protected ScreeningResultEntity() {
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

  ScreeningStatus getStatus() {
    return status;
  }

  void setStatus(ScreeningStatus status) {
    this.status = status;
  }

  String getHitsJson() {
    return hitsJson;
  }

  void setHitsJson(String hitsJson) {
    this.hitsJson = hitsJson;
  }

  Instant getCheckedAt() {
    return checkedAt;
  }

  void setCheckedAt(Instant checkedAt) {
    this.checkedAt = checkedAt;
  }
}
