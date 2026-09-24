package com.barrier.webhook.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.webhookdelivery.intake.DeliveryIntake;
import com.barrier.webhookdelivery.intake.DeliveryRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AssessmentCompletedListenerTest {

  @Mock DeliveryIntake intake;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private AssessmentCompletedListener listener() {
    return new AssessmentCompletedListener(intake, objectMapper);
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
    String payload = "{\"status\":\"APROVADO\",\"tenantId\":\"acme\",\"subjectId\":\"sub-1\"}";
    EventEnvelope envelope =
        new EventEnvelope(
            java.util.UUID.randomUUID(),
            "barrier.assessment.completed",
            "aid",
            Instant.now(),
            1,
            payload,
            "corr-1");

    listener().onMessage(objectMapper.writeValueAsString(envelope));

    ArgumentCaptor<DeliveryRequest> captor = ArgumentCaptor.forClass(DeliveryRequest.class);
    verify(intake).accept(captor.capture());
    DeliveryRequest request = captor.getValue();
    assertThat(request.tenantId()).isEqualTo("acme");
    assertThat(request.eventType()).isEqualTo("barrier.assessment.completed");
    assertThat(request.aggregateId()).isEqualTo("aid");
    assertThat(request.partitionKey()).isEqualTo("sub-1");
    assertThat(request.correlationId()).isEqualTo("corr-1");
  }

  /**
   * Regressão do modo de falha mais caro do consumo: engolir a exceção commitava o offset, e a
   * decisão de KYC sumia para sempre. Agora ela sobe — o error handler retenta sem commitar.
   */
  @Test
  void falhaTransitoriaSobeParaNaoCommitarOOffset() {
    doThrow(new IllegalStateException("banco fora do ar")).when(intake).accept(any());

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

  /**
   * Evento sem tenantId no payload: {@code DeliveryRequest} exige tenantId e lança {@code
   * IllegalArgumentException} — o listener a traduz para {@link MalformedEventException}, porque
   * não há conserto possível (a lib não tem para onde entregar sem tenant).
   */
  @Test
  void payloadSemTenantViraMalformedEventException() {
    EventEnvelope envelope =
        EventEnvelope.of("barrier.assessment.completed", "aid", 1, "{\"status\":\"APROVADO\"}");

    assertThatThrownBy(() -> listener().onMessage(objectMapper.writeValueAsString(envelope)))
        .isInstanceOf(MalformedEventException.class);
  }
}
