package com.barrier.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.webhook.client.HmacSigner;
import com.barrier.webhook.client.WebhookClient;
import com.barrier.webhook.client.WebhookRequest;
import com.barrier.webhook.client.WebhookSendResult;
import com.barrier.webhook.config.WebhookProperties;
import com.barrier.webhook.domain.Delivery;
import com.barrier.webhook.domain.DeliveryStatus;
import com.barrier.webhook.repository.DeliveryRepository;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryServiceTest {

  @Mock DeliveryRepository repository;
  @Mock WebhookClient client;

  private final HmacSigner signer = new HmacSigner();

  private WebhookDeliveryService service(String targetUrl) {
    return new WebhookDeliveryService(
        repository,
        client,
        signer,
        new WebhookProperties(targetUrl, "secret", 5, Duration.ofSeconds(1)));
  }

  private EventEnvelope event() {
    return EventEnvelope.of("barrier.assessment.completed", "aid", 1, "{\"status\":\"APROVADO\"}");
  }

  @Test
  void entregaComSucessoMarcaDelivered() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);
    when(repository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
    when(client.send(any(WebhookRequest.class))).thenReturn(WebhookSendResult.ok(200));

    service("http://client/webhook").onEvent(event());

    ArgumentCaptor<Delivery> saved = ArgumentCaptor.forClass(Delivery.class);
    verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(DeliveryStatus.DELIVERED);
  }

  @Test
  void falhaMarcaFailedComRetry() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);
    when(repository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
    when(client.send(any(WebhookRequest.class)))
        .thenReturn(WebhookSendResult.failure(500, "erro"));

    service("http://client/webhook").onEvent(event());

    ArgumentCaptor<Delivery> saved = ArgumentCaptor.forClass(Delivery.class);
    verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(saved.getValue().nextAttemptAt()).isNotNull();
  }

  @Test
  void eventoDuplicadoNaoEntrega() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(true);

    service("http://client/webhook").onEvent(event());

    verify(client, never()).send(any());
    verify(repository, never()).save(any());
  }

  @Test
  void semEndpointConfiguradoNaoEntrega() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);

    service("").onEvent(event());

    verify(client, never()).send(any());
    verify(repository, never()).save(any());
  }
}
