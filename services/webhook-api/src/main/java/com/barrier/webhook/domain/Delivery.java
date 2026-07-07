package com.barrier.webhook.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Entrega de um webhook para o cliente. Objeto de domínio (sem JPA); a persistência é feita
 * por um adapter no pacote {@code repository}.
 */
public class Delivery {

  private final UUID id;
  private final UUID eventId;
  private final String assessmentId;
  private final String targetUrl;
  private final String payload;
  private DeliveryStatus status;
  private int attempts;
  private String lastError;
  private Instant nextAttemptAt;
  private final Instant createdAt;
  private Instant deliveredAt;

  private Delivery(
      UUID id,
      UUID eventId,
      String assessmentId,
      String targetUrl,
      String payload,
      Instant createdAt) {
    this.id = id;
    this.eventId = eventId;
    this.assessmentId = assessmentId;
    this.targetUrl = targetUrl;
    this.payload = payload;
    this.status = DeliveryStatus.PENDING;
    this.attempts = 0;
    this.nextAttemptAt = createdAt;
    this.createdAt = createdAt;
  }

  /** Cria uma entrega pendente para o evento. */
  public static Delivery create(
      UUID eventId, String assessmentId, String targetUrl, String payload) {
    return new Delivery(
        UUID.randomUUID(), eventId, assessmentId, targetUrl, payload, Instant.now());
  }

  /** Reconstrói a partir da persistência. */
  public static Delivery rehydrate(
      UUID id,
      UUID eventId,
      String assessmentId,
      String targetUrl,
      String payload,
      DeliveryStatus status,
      int attempts,
      String lastError,
      Instant nextAttemptAt,
      Instant createdAt,
      Instant deliveredAt) {
    Delivery d = new Delivery(id, eventId, assessmentId, targetUrl, payload, createdAt);
    d.status = status;
    d.attempts = attempts;
    d.lastError = lastError;
    d.nextAttemptAt = nextAttemptAt;
    d.deliveredAt = deliveredAt;
    return d;
  }

  /** Marca como entregue com sucesso. */
  public void markDelivered() {
    this.attempts++;
    this.status = DeliveryStatus.DELIVERED;
    this.lastError = null;
    this.nextAttemptAt = null;
    this.deliveredAt = Instant.now();
  }

  /** Registra falha: reagenda se ainda há tentativas, senão marca como morta. */
  public void markFailed(String error, int maxAttempts, Instant nextAttemptAt) {
    this.attempts++;
    this.lastError = error;
    if (this.attempts >= maxAttempts) {
      this.status = DeliveryStatus.DEAD;
      this.nextAttemptAt = null;
    } else {
      this.status = DeliveryStatus.FAILED;
      this.nextAttemptAt = nextAttemptAt;
    }
  }

  public UUID id() {
    return id;
  }

  public UUID eventId() {
    return eventId;
  }

  public String assessmentId() {
    return assessmentId;
  }

  public String targetUrl() {
    return targetUrl;
  }

  public String payload() {
    return payload;
  }

  public DeliveryStatus status() {
    return status;
  }

  public int attempts() {
    return attempts;
  }

  public String lastError() {
    return lastError;
  }

  public Instant nextAttemptAt() {
    return nextAttemptAt;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant deliveredAt() {
    return deliveredAt;
  }
}
