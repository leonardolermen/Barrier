package com.barrier.riskengine.device.service;

import com.barrier.riskengine.device.repository.DeviceSeenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra o device usado numa avaliação e informa quantos subjects distintos usaram o mesmo
 * device recentemente (janela configurável) — sinal de fraude por múltiplas contas.
 */
@Service
public class DeviceSeenService {

  private final DeviceSeenRepository repository;
  private final Clock clock;
  private final Duration window;

  public DeviceSeenService(
      DeviceSeenRepository repository,
      Clock clock,
      @Value("${barrier.risk.device-reuse-window-days:30}") long windowDays) {
    this.repository = repository;
    this.clock = clock;
    this.window = Duration.ofDays(windowDays);
  }

  /**
   * Grava o device desta avaliação e devolve quantos subjects distintos (incluindo este) usaram
   * o mesmo device no tenant dentro da janela configurada.
   */
  @Transactional
  public long recordAndCountDistinctSubjects(String tenantId, String deviceId, UUID subjectId) {
    repository.record(tenantId, deviceId, subjectId);
    return repository.countDistinctSubjects(tenantId, deviceId, Instant.now(clock).minus(window));
  }
}
