package com.barrier.commons.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope padrão de todo evento de domínio publicado no Barrier.
 *
 * <p>Carrega os metadados exigidos pelos padrões do projeto: identificador único do evento
 * (idempotência), id de correlação da avaliação, instante de ocorrência, versão do contrato
 * e o payload já serializado.
 *
 * @param eventId identificador único do evento (usado para idempotência no consumo)
 * @param type nome canônico do evento, ex.: {@code barrier.assessment.completed}
 * @param assessmentId id de correlação da avaliação; também usado como chave no Kafka
 * @param occurredAt instante em que o evento ocorreu
 * @param version versão do contrato do payload
 * @param payload conteúdo do evento já serializado (ex.: JSON)
 * @param correlationId id da requisição que originou o evento; {@code null} em eventos gravados
 *     antes da correlação existir. É o que liga o {@code POST} do cliente à decisão e à entrega do
 *     webhook num único fio, atravessando duas threads e um broker
 */
public record EventEnvelope(
    UUID eventId,
    String type,
    String assessmentId,
    Instant occurredAt,
    int version,
    String payload,
    String correlationId) {

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(assessmentId, "assessmentId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(payload, "payload");
    if (version < 1) {
      throw new IllegalArgumentException("version deve ser >= 1");
    }
  }

  /** Envelope sem correlação — eventos anteriores à propagação do id, e testes. */
  public EventEnvelope(
      UUID eventId,
      String type,
      String assessmentId,
      Instant occurredAt,
      int version,
      String payload) {
    this(eventId, type, assessmentId, occurredAt, version, payload, null);
  }

  /** Cria um envelope novo com {@code eventId} aleatório e {@code occurredAt} = agora. */
  public static EventEnvelope of(String type, String assessmentId, int version, String payload) {
    return new EventEnvelope(
        UUID.randomUUID(), type, assessmentId, Instant.now(), version, payload);
  }
}
