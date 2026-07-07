package com.barrier.webhook.repository;

import com.barrier.webhook.domain.DeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento JPA de uma entrega de webhook. */
@Entity
@Table(name = "deliveries")
class DeliveryEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Column(name = "assessment_id", nullable = false, length = 64)
  private String assessmentId;

  @Column(name = "tenant_id", length = 40)
  private String tenantId;

  @Column(name = "target_url", nullable = false, length = 500)
  private String targetUrl;

  @Column(name = "payload", nullable = false, length = 4000)
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private DeliveryStatus status;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "last_error", length = 500)
  private String lastError;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  protected DeliveryEntity() {
    // JPA
  }

  UUID getId() {
    return id;
  }

  void setId(UUID id) {
    this.id = id;
  }

  UUID getEventId() {
    return eventId;
  }

  void setEventId(UUID eventId) {
    this.eventId = eventId;
  }

  String getAssessmentId() {
    return assessmentId;
  }

  void setAssessmentId(String assessmentId) {
    this.assessmentId = assessmentId;
  }

  String getTenantId() {
    return tenantId;
  }

  void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  String getTargetUrl() {
    return targetUrl;
  }

  void setTargetUrl(String targetUrl) {
    this.targetUrl = targetUrl;
  }

  String getPayload() {
    return payload;
  }

  void setPayload(String payload) {
    this.payload = payload;
  }

  DeliveryStatus getStatus() {
    return status;
  }

  void setStatus(DeliveryStatus status) {
    this.status = status;
  }

  int getAttempts() {
    return attempts;
  }

  void setAttempts(int attempts) {
    this.attempts = attempts;
  }

  String getLastError() {
    return lastError;
  }

  void setLastError(String lastError) {
    this.lastError = lastError;
  }

  Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  void setNextAttemptAt(Instant nextAttemptAt) {
    this.nextAttemptAt = nextAttemptAt;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  Instant getDeliveredAt() {
    return deliveredAt;
  }

  void setDeliveredAt(Instant deliveredAt) {
    this.deliveredAt = deliveredAt;
  }
}
