package com.barrier.webhook.repository;

import com.barrier.webhook.domain.Delivery;
import com.barrier.webhook.domain.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

@Repository
class DeliveryRepositoryImpl implements DeliveryRepository {

  private final DeliveryJpaRepository jpa;

  DeliveryRepositoryImpl(DeliveryJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Delivery save(Delivery delivery) {
    return DeliveryEntityMapper.toDomain(jpa.save(DeliveryEntityMapper.toEntity(delivery)));
  }

  @Override
  public boolean existsByEventId(UUID eventId) {
    return jpa.existsByEventId(eventId);
  }

  @Override
  public List<Delivery> findDue(Instant now, int limit) {
    return jpa
        .findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            List.of(DeliveryStatus.PENDING, DeliveryStatus.FAILED), now, Limit.of(limit))
        .stream()
        .map(DeliveryEntityMapper::toDomain)
        .toList();
  }
}
