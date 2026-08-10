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
  private final String tenantId;
  private final String targetUrl;
  private final String payload;
  private DeliveryStatus status;
  private int attempts;
  private String lastError;
  private Instant nextAttemptAt;
  private Instant claimedAt;
  private final Instant createdAt;
  private Instant deliveredAt;

  private Delivery(
      UUID id,
      UUID eventId,
      String assessmentId,
      String tenantId,
      String targetUrl,
      String payload,
      Instant createdAt) {
    this.id = id;
    this.eventId = eventId;
    this.assessmentId = assessmentId;
    this.tenantId = tenantId;
    this.targetUrl = targetUrl;
    this.payload = payload;
    this.status = DeliveryStatus.PENDING;
    this.attempts = 0;
    this.nextAttemptAt = createdAt;
    // Nasce reivindicada: quem cria é quem tenta a entrega em seguida. Sem isso, a linha já nascia
    // vencida (next_attempt_at = created_at) e o scheduler a pegava enquanto o POST original ainda
    // estava em andamento — entrega dobrada numa instância só. Ver migration V003.
    this.claimedAt = createdAt;
    this.createdAt = createdAt;
  }

  /** Cria uma entrega pendente para o evento. */
  public static Delivery create(
      UUID eventId, String assessmentId, String tenantId, String targetUrl, String payload) {
    return new Delivery(
        UUID.randomUUID(), eventId, assessmentId, tenantId, targetUrl, payload, Instant.now());
  }

  /** Reconstrói a partir da persistência. */
  public static Delivery rehydrate(
      UUID id,
      UUID eventId,
      String assessmentId,
      String tenantId,
      String targetUrl,
      String payload,
      DeliveryStatus status,
      int attempts,
      String lastError,
      Instant nextAttemptAt,
      Instant claimedAt,
      Instant createdAt,
      Instant deliveredAt) {
    Delivery d = new Delivery(id, eventId, assessmentId, tenantId, targetUrl, payload, createdAt);
    d.status = status;
    d.attempts = attempts;
    d.lastError = lastError;
    d.nextAttemptAt = nextAttemptAt;
    d.claimedAt = claimedAt;
    d.deliveredAt = deliveredAt;
    return d;
  }

  /** Marca como entregue com sucesso. */
  public void markDelivered() {
    this.attempts++;
    this.status = DeliveryStatus.DELIVERED;
    this.lastError = null;
    this.nextAttemptAt = null;
    this.claimedAt = null;
    this.deliveredAt = Instant.now();
  }

  /**
   * Registra falha: reagenda se ainda há tentativas, senão marca como morta.
   *
   * <p>Libera a posse em qualquer caso: quem governa a próxima tentativa é o {@code nextAttemptAt}
   * (backoff exponencial), não a lease — que existe só para devolver à fila uma entrega cuja
   * instância morreu no meio do POST.
   */
  public void markFailed(String error, int maxAttempts, Instant nextAttemptAt) {
    this.attempts++;
    this.lastError = error;
    this.claimedAt = null;
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

  public String tenantId() {
    return tenantId;
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

  public Instant claimedAt() {
    return claimedAt;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant deliveredAt() {
    return deliveredAt;
  }
}
