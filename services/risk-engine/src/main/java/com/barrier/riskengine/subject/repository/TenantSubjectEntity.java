package com.barrier.riskengine.subject.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Vínculo de visibilidade entre um tenant e um subject. */
@Entity
@Table(name = "tenant_subjects")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantSubjectEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  TenantSubjectEntity(UUID id, String tenantId, UUID subjectId, Instant now) {
    this.id = id;
    this.tenantId = tenantId;
    this.subjectId = subjectId;
    this.firstSeenAt = now;
    this.lastSeenAt = now;
  }

  void touch(Instant now) {
    this.lastSeenAt = now;
  }
}
