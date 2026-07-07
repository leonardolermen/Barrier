package com.barrier.webhook.controller;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.webhook.service.WebhookDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Consome {@code barrier.assessment.completed} e aciona a entrega do webhook. */
@Component
public class AssessmentCompletedListener {

  static final String TOPIC = "barrier.assessment.completed";

  private static final Logger log = LoggerFactory.getLogger(AssessmentCompletedListener.class);

  private final WebhookDeliveryService service;
  private final ObjectMapper objectMapper;

  public AssessmentCompletedListener(WebhookDeliveryService service, ObjectMapper objectMapper) {
    this.service = service;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(topics = TOPIC, groupId = "${spring.kafka.consumer.group-id}")
  public void onMessage(String message) {
    try {
      EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
      service.onEvent(envelope);
    } catch (RuntimeException e) {
      // Não relança: evita loop de reentrega por mensagem malformada. Fica logado para análise.
      log.error("Falha ao processar evento de avaliação concluída", e);
    }
  }
}
