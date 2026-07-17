package com.barrier.riskengine.device.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.device.repository.DeviceSeenRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeviceSeenServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock DeviceSeenRepository repository;

  @Test
  void gravaODeviceEDevolveAContagem() {
    UUID subjectId = UUID.randomUUID();
    when(repository.countDistinctSubjects(eq("acme"), eq("dev-1"), any(Instant.class)))
        .thenReturn(4L);

    var service = new DeviceSeenService(repository, FIXED, 30);
    long count = service.recordAndCountDistinctSubjects("acme", "dev-1", subjectId);

    assertThat(count).isEqualTo(4L);
    verify(repository).record("acme", "dev-1", subjectId);
  }
}
