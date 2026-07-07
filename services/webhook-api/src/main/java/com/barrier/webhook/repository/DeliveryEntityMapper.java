package com.barrier.webhook.repository;

import com.barrier.webhook.domain.Delivery;

final class DeliveryEntityMapper {

  private DeliveryEntityMapper() {}

  static DeliveryEntity toEntity(Delivery d) {
    DeliveryEntity e = new DeliveryEntity();
    e.setId(d.id());
    e.setEventId(d.eventId());
    e.setAssessmentId(d.assessmentId());
    e.setTargetUrl(d.targetUrl());
    e.setPayload(d.payload());
    e.setStatus(d.status());
    e.setAttempts(d.attempts());
    e.setLastError(d.lastError());
    e.setNextAttemptAt(d.nextAttemptAt());
    e.setCreatedAt(d.createdAt());
    e.setDeliveredAt(d.deliveredAt());
    return e;
  }

  static Delivery toDomain(DeliveryEntity e) {
    return Delivery.rehydrate(
        e.getId(),
        e.getEventId(),
        e.getAssessmentId(),
        e.getTargetUrl(),
        e.getPayload(),
        e.getStatus(),
        e.getAttempts(),
        e.getLastError(),
        e.getNextAttemptAt(),
        e.getCreatedAt(),
        e.getDeliveredAt());
  }
}
