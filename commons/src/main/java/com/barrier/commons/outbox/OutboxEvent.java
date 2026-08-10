package com.barrier.commons.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento pendente de publicação, gravado na mesma transação da mudança de estado
 * (transactional outbox). Um relay publica no Kafka e marca como {@link OutboxStatus#SENT}.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "aggregate_id", nullable = false, length = 64)
  private String aggregateId;

  @Column(name = "type", nullable = false, length = 120)
  private String type;

  @Column(name = "payload", nullable = false, length = 4000)
  private String payload;

  @Column(name = "version", nullable = false)
  private int version;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OutboxStatus status;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "sent_at")
  private Instant sentAt;

  /**
   * Posse desta linha por um relay, com expiração. Substitui o lock de banco que era mantido
   * durante a publicação — ver migration V025.
   */
  @Column(name = "claimed_at")
  private Instant claimedAt;

  /** Correlação da requisição que originou o evento; viaja até o consumidor. */
  @Column(name = "correlation_id", length = 64)
  private String correlationId;

  protected OutboxEvent() {
    // JPA
  }

  private OutboxEvent(
      UUID id,
      String aggregateId,
      String type,
      String payload,
      int version,
      Instant occurredAt,
      String correlationId) {
    this.id = id;
    this.aggregateId = aggregateId;
    this.type = type;
    this.payload = payload;
    this.version = version;
    this.occurredAt = occurredAt;
    this.correlationId = correlationId;
    this.createdAt = Instant.now();
    this.status = OutboxStatus.PENDING;
    this.attempts = 0;
  }

  /** Cria um evento pendente com id aleatório. */
  public static OutboxEvent pending(
      String aggregateId,
      String type,
      String payload,
      int version,
      Instant occurredAt,
      String correlationId) {
    return new OutboxEvent(
        UUID.randomUUID(), aggregateId, type, payload, version, occurredAt, correlationId);
  }

  /** Reivindica a linha para publicação por esta instância, iniciando a lease. */
  public void markClaimed(Instant now) {
    this.claimedAt = now;
  }

  /** Marca como enviado após publicação bem-sucedida. */
  public void markSent() {
    this.status = OutboxStatus.SENT;
    this.sentAt = Instant.now();
    this.attempts++;
  }

  /**
   * Registra uma tentativa de publicação que falhou e <b>libera a posse</b>, para que o próximo
   * ciclo possa tentar de novo sem esperar a lease expirar — a lease existe para sobreviver à
   * morte da instância, não para servir de backoff.
   */
  public void markFailedAttempt() {
    this.attempts++;
    this.claimedAt = null;
  }

  public UUID getId() {
    return id;
  }

  public String getAggregateId() {
    return aggregateId;
  }

  public String getType() {
    return type;
  }

  public String getPayload() {
    return payload;
  }

  public int getVersion() {
    return version;
  }

  public OutboxStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public Instant getClaimedAt() {
    return claimedAt;
  }

  public String getCorrelationId() {
    return correlationId;
  }
}
