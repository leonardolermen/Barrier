package com.barrier.riskengine.device.repository;

import java.time.Instant;
import java.util.UUID;

/** Repositório de domínio do histórico de devices vistos por avaliação. */
public interface DeviceSeenRepository {

  void record(String tenantId, String deviceId, UUID subjectId);

  /** Quantos subjects distintos usaram este device, no tenant, desde {@code since}. */
  long countDistinctSubjects(String tenantId, String deviceId, Instant since);
}
