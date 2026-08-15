package com.barrier.webhook.controller;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.commons.observability.Correlation;
import com.barrier.webhook.service.WebhookDeliveryService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consome {@code barrier.assessment.completed} e aciona a entrega do webhook.
 *
 * <p>Antes este método capturava <b>toda</b> {@code RuntimeException} e retornava normalmente, o
 * que commitava o offset: com o banco fora do ar por trinta segundos, cada decisão de KYC que
 * passasse nesse intervalo era perdida em definitivo — sem entrega, sem registro, sem rastro além
 * de uma linha de log. O motivo original de engolir era legítimo (mensagem malformada retentada
 * infinitamente trava a partição), mas a cura valia para as duas falhas, e só uma delas merecia.
 *
 * <p>Agora a distinção é explícita: o que não tem conserto vira {@link MalformedEventException} e
 * vai direto para a DLT; o resto sobe, e o {@code KafkaErrorHandlingConfig} retenta com backoff
 * <b>sem commitar</b>. Esgotadas as tentativas, o evento também vai para a DLT — ficar preso na
 * partição pararia a entrega de todos os outros tenants —, e aí é a reconciliação
 * ({@code DeliveryReconciliationJob}) que fecha a lacuna.
 */
@Component
public class AssessmentCompletedListener {

  static final String TOPIC = "barrier.assessment.completed";

  /**
   * Mudança de nível de risco corrente do cliente (fila-origem F4). Mesmo consumidor e mesmo grupo
   * dos desfechos: a máquina de entrega é a mesma (endpoint por tenant, HMAC, retry, idempotência
   * por {@code eventId}), e o que muda é só o tipo do fato que chega. Um consumer-group por
   * <i>consumidor</i> é a lição do {@code tzofe}; um por tópico entregue ao mesmo destino não é.
   */
  static final String RISK_LEVEL_TOPIC = "barrier.subject.risk_level_changed";

  private static final Logger log = LoggerFactory.getLogger(AssessmentCompletedListener.class);

  private final WebhookDeliveryService service;
  private final ObjectMapper objectMapper;

  public AssessmentCompletedListener(WebhookDeliveryService service, ObjectMapper objectMapper) {
    this.service = service;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(topics = {TOPIC, RISK_LEVEL_TOPIC}, groupId = "${spring.kafka.consumer.group-id}")
  public void onMessage(String message) {
    EventEnvelope envelope = parse(message);
    String tenantId = extractTenantId(envelope.payload());
    // Fecha o fio: o mesmo id que saiu do POST no risk-engine aparece no log da entrega.
    Correlation.run(envelope.correlationId(), () -> service.onEvent(envelope, tenantId));
  }

  private EventEnvelope parse(String message) {
    try {
      return objectMapper.readValue(message, EventEnvelope.class);
    } catch (RuntimeException e) {
      log.error("Evento ilegível na fila; será enviado para a DLT", e);
      throw new MalformedEventException("Envelope de evento ilegível", e);
    }
  }

  private String extractTenantId(String payload) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> data = objectMapper.readValue(payload, Map.class);
      Object tenantId = data.get("tenantId");
      return tenantId == null ? null : tenantId.toString();
    } catch (RuntimeException e) {
      throw new MalformedEventException("Payload do evento ilegível", e);
    }
  }
}
