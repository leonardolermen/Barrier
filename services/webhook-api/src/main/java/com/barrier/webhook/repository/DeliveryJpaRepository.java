package com.barrier.webhook.repository;

import com.barrier.webhook.domain.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface DeliveryJpaRepository extends JpaRepository<DeliveryEntity, UUID> {

  boolean existsByEventId(UUID eventId);

  List<DeliveryEntity> findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
      List<DeliveryStatus> statuses, Instant now, Limit limit);
}
