package com.barrier.riskengine.device.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DeviceSeenJpaRepository extends JpaRepository<DeviceSeenEntity, UUID> {

  @Query(
      "select count(distinct d.subjectId) from DeviceSeenEntity d "
          + "where d.tenantId = :tenantId and d.deviceId = :deviceId and d.seenAt >= :since")
  long countDistinctSubjects(
      @Param("tenantId") String tenantId,
      @Param("deviceId") String deviceId,
      @Param("since") Instant since);
}
