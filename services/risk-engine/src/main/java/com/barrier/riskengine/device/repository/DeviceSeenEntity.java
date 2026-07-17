package com.barrier.riskengine.device.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento JPA de um registro de device visto numa avaliação. */
@Entity
@Table(name = "device_seen")
class DeviceSeenEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "device_id", nullable = false, length = 200)
  private String deviceId;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(name = "seen_at", nullable = false)
  private Instant seenAt;

  protected DeviceSeenEntity() {
    // JPA
  }

  DeviceSeenEntity(UUID id, String tenantId, String deviceId, UUID subjectId, Instant seenAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.deviceId = deviceId;
    this.subjectId = subjectId;
    this.seenAt = seenAt;
  }
}
