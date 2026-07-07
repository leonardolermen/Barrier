package com.barrier.webhook.repository;

import com.barrier.webhook.domain.Delivery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Repositório de domínio das entregas. */
public interface DeliveryRepository {

  Delivery save(Delivery delivery);

  boolean existsByEventId(UUID eventId);

  /** Entregas prontas para (re)tentativa: PENDING ou FAILED com {@code nextAttemptAt} vencido. */
  List<Delivery> findDue(Instant now, int limit);
}
