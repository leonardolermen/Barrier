package com.barrier.webhook.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.webhook.service.WebhookDeliveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AssessmentCompletedListenerTest {

  @Mock WebhookDeliveryService service;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private AssessmentCompletedListener listener() {
    return new AssessmentCompletedListener(service, objectMapper);
  }

  private String mensagem() {
    EventEnvelope envelope =
        EventEnvelope.of(
            "barrier.assessment.completed",
            "aid",
            1,
            "{\"status\":\"APROVADO\",\"tenantId\":\"acme\"}");
    return objectMapper.writeValueAsString(envelope);
  }

  @Test
  void entregaEventoValidoComOTenantDoPayload() {
    listener().onMessage(mensagem());

    verify(service).onEvent(any(EventEnvelope.class), org.mockito.ArgumentMatchers.eq("acme"));
  }

  /**
   * Regressão do modo de falha mais caro do consumo: engolir a exceção commitava o offset, e a
   * decisão de KYC sumia para sempre. Agora ela sobe — o error handler retenta sem commitar.
   */
  @Test
  void falhaTransitoriaSobeParaNaoCommitarOOffset() {
    doThrow(new IllegalStateException("banco fora do ar"))
        .when(service)
        .onEvent(any(EventEnvelope.class), any());

    assertThatThrownBy(() -> listener().onMessage(mensagem()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("banco fora do ar");
  }

  /** Mensagem sem conserto é sinalizada como tal: retentá-la prenderia a partição para sempre. */
  @Test
  void mensagemMalformadaViraMalformedEventException() {
    assertThatThrownBy(() -> listener().onMessage("isto não é json"))
        .isInstanceOf(MalformedEventException.class);
  }

  @Test
  void payloadIlegivelTambemViraMalformedEventException() {
    EventEnvelope envelope =
        EventEnvelope.of("barrier.assessment.completed", "aid", 1, "nao-e-json");

    assertThatThrownBy(() -> listener().onMessage(objectMapper.writeValueAsString(envelope)))
        .isInstanceOf(MalformedEventException.class);
  }

  /** Evento sem tenantId no payload não é malformado — segue e a resolução de destino decide. */
  @Test
  void payloadSemTenantSegue() {
    EventEnvelope envelope =
        EventEnvelope.of("barrier.assessment.completed", "aid", 1, "{\"status\":\"APROVADO\"}");

    assertThatCode(() -> listener().onMessage(objectMapper.writeValueAsString(envelope)))
        .doesNotThrowAnyException();
    verify(service).onEvent(any(EventEnvelope.class), org.mockito.ArgumentMatchers.isNull());
  }
}
