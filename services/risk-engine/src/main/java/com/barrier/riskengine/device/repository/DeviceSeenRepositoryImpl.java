package com.barrier.riskengine.device.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class DeviceSeenRepositoryImpl implements DeviceSeenRepository {

  private final DeviceSeenJpaRepository jpa;

  DeviceSeenRepositoryImpl(DeviceSeenJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public void record(String tenantId, String deviceId, UUID subjectId) {
    jpa.save(new DeviceSeenEntity(UUID.randomUUID(), tenantId, deviceId, subjectId, Instant.now()));
  }

  @Override
  public long countDistinctSubjects(String tenantId, String deviceId, Instant since) {
    return jpa.countDistinctSubjects(tenantId, deviceId, since);
  }
}
