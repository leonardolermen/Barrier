package com.barrier.webhook.service;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.webhook.client.HmacSigner;
import com.barrier.webhook.client.WebhookClient;
import com.barrier.webhook.client.WebhookRequest;
import com.barrier.webhook.client.WebhookSendResult;
import com.barrier.webhook.config.WebhookProperties;
import com.barrier.webhook.domain.Delivery;
import com.barrier.webhook.repository.DeliveryRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Entrega os resultados de avaliação ao endpoint do cliente.
 *
 * <p>Idempotência por {@code eventId} (Kafka é at-least-once). A entrega é assinada com HMAC;
 * falhas reagendam com backoff exponencial até esgotar as tentativas.
 */
@Service
public class WebhookDeliveryService {

  private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
  private static final int RETRY_BATCH = 100;

  private final DeliveryRepository repository;
  private final WebhookClient client;
  private final HmacSigner signer;
  private final WebhookProperties properties;

  public WebhookDeliveryService(
      DeliveryRepository repository,
      WebhookClient client,
      HmacSigner signer,
      WebhookProperties properties) {
    this.repository = repository;
    this.client = client;
    this.signer = signer;
    this.properties = properties;
  }

  /** Recebe um evento de avaliação concluída e tenta entregá-lo, no escopo de um tenant. */
  public void onEvent(EventEnvelope envelope, String tenantId) {
    if (repository.existsByEventId(envelope.eventId())) {
      log.debug("Evento {} já registrado; ignorando (idempotência)", envelope.eventId());
      return;
    }
    if (properties.targetUrl() == null || properties.targetUrl().isBlank()) {
      log.warn("Nenhum endpoint de webhook configurado; evento {} não entregue", envelope.eventId());
      return;
    }

    Delivery delivery;
    try {
      delivery =
          repository.save(
              Delivery.create(
                  envelope.eventId(),
                  envelope.assessmentId(),
                  tenantId,
                  properties.targetUrl(),
                  envelope.payload()));
    } catch (DataIntegrityViolationException e) {
      log.debug("Entrega concorrente para o evento {}; ignorando", envelope.eventId());
      return;
    }
    attempt(delivery);
  }

  /** Reprocessa entregas vencidas (agendado). Retorna quantas foram tentadas. */
  public int retryDue() {
    List<Delivery> due = repository.findDue(Instant.now(), RETRY_BATCH);
    due.forEach(this::attempt);
    return due.size();
  }

  private void attempt(Delivery delivery) {
    String signature = signer.sign(delivery.payload(), properties.secret());
    WebhookSendResult result =
        client.send(
            new WebhookRequest(
                delivery.targetUrl(),
                delivery.payload(),
                delivery.eventId().toString(),
                signature));

    if (result.success()) {
      delivery.markDelivered();
      log.info("Webhook do evento {} entregue ({})", delivery.eventId(), result.statusCode());
    } else {
      delivery.markFailed(result.detail(), properties.maxAttempts(), nextAttempt(delivery.attempts()));
      log.warn(
          "Falha ao entregar evento {} (tentativa {}): {}",
          delivery.eventId(),
          delivery.attempts() + 1,
          result.detail());
    }
    repository.save(delivery);
  }

  private Instant nextAttempt(int attempts) {
    long factor = 1L << Math.min(attempts, 6); // backoff exponencial, teto no 64x
    return Instant.now().plus(properties.baseBackoff().multipliedBy(factor));
  }
}
