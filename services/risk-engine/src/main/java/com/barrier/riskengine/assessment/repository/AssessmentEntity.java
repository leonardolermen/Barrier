package com.barrier.riskengine.assessment.repository;

import com.barrier.riskengine.assessment.domain.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.DocumentType;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento JPA da avaliação. Não vaza para fora da camada de repositório. */
@Entity
@Table(name = "assessments")
class AssessmentEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "subject_id")
  private UUID subjectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "document_type", nullable = false, length = 10)
  private DocumentType documentType;

  @Column(name = "document_value", nullable = false, length = 20)
  private String documentValue;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "ip", length = 45)
  private String ip;

  @Column(name = "device_id", length = 200)
  private String deviceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private AssessmentStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk_level", length = 10)
  private RiskLevel riskLevel;

  @Column(name = "decision", length = 200)
  private String decision;

  @Column(name = "factors", length = 2000)
  private String factors;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "reviewed_by", length = 200)
  private String reviewedBy;

  @Column(name = "review_reason", length = 500)
  private String reviewReason;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  protected AssessmentEntity() {
    // JPA
  }

  UUID getId() {
    return id;
  }

  void setId(UUID id) {
    this.id = id;
  }

  String getTenantId() {
    return tenantId;
  }

  void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  UUID getSubjectId() {
    return subjectId;
  }

  void setSubjectId(UUID subjectId) {
    this.subjectId = subjectId;
  }

  DocumentType getDocumentType() {
    return documentType;
  }

  void setDocumentType(DocumentType documentType) {
    this.documentType = documentType;
  }

  String getDocumentValue() {
    return documentValue;
  }

  void setDocumentValue(String documentValue) {
    this.documentValue = documentValue;
  }

  String getName() {
    return name;
  }

  void setName(String name) {
    this.name = name;
  }

  String getIp() {
    return ip;
  }

  void setIp(String ip) {
    this.ip = ip;
  }

  String getDeviceId() {
    return deviceId;
  }

  void setDeviceId(String deviceId) {
    this.deviceId = deviceId;
  }

  AssessmentStatus getStatus() {
    return status;
  }

  void setStatus(AssessmentStatus status) {
    this.status = status;
  }

  RiskLevel getRiskLevel() {
    return riskLevel;
  }

  void setRiskLevel(RiskLevel riskLevel) {
    this.riskLevel = riskLevel;
  }

  String getDecision() {
    return decision;
  }

  void setDecision(String decision) {
    this.decision = decision;
  }

  String getFactors() {
    return factors;
  }

  void setFactors(String factors) {
    this.factors = factors;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  Instant getCompletedAt() {
    return completedAt;
  }

  void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  String getReviewedBy() {
    return reviewedBy;
  }

  void setReviewedBy(String reviewedBy) {
    this.reviewedBy = reviewedBy;
  }

  String getReviewReason() {
    return reviewReason;
  }

  void setReviewReason(String reviewReason) {
    this.reviewReason = reviewReason;
  }

  Instant getReviewedAt() {
    return reviewedAt;
  }

  void setReviewedAt(Instant reviewedAt) {
    this.reviewedAt = reviewedAt;
  }
}
