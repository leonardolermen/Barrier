package com.barrier.riskengine.behavior.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Mapeamento JPA do fato comportamental. */
@Entity
@Table(name = "behavior_events")
@Getter(AccessLevel.PACKAGE)
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BehaviorEventEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(name = "event_type", nullable = false, length = 60)
  private String eventType;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload")
  private String payload;

  @Column(name = "source_event_id", nullable = false, length = 120)
  private String sourceEventId;
}
