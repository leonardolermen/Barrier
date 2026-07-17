package com.barrier.riskengine.history.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.barrier.riskengine.history.domain.HistoryEventType;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subject_history")
class SubjectHistoryEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 40)
  private HistoryEventType eventType;

  @Column(name = "detail", length = 500)
  private String detail;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected SubjectHistoryEntity() {
    // JPA
  }

  SubjectHistoryEntity(
      UUID id,
      UUID subjectId,
      HistoryEventType eventType,
      String detail,
      Instant occurredAt,
      Instant createdAt) {
    this.id = id;
    this.subjectId = subjectId;
    this.eventType = eventType;
    this.detail = detail;
    this.occurredAt = occurredAt;
    this.createdAt = createdAt;
  }

  UUID getId() {
    return id;
  }

  UUID getSubjectId() {
    return subjectId;
  }

  HistoryEventType getEventType() {
    return eventType;
  }

  String getDetail() {
    return detail;
  }

  Instant getOccurredAt() {
    return occurredAt;
  }

  Instant getCreatedAt() {
    return createdAt;
  }
}
