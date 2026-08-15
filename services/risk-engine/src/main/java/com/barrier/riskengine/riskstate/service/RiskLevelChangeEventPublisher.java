package com.barrier.riskengine.riskstate.service;

import com.barrier.commons.outbox.OutboxRecorder;
import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.riskstate.domain.RiskLevelTransition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publica {@code barrier.subject.risk_level_changed} na outbox, na mesma transação em que a
 * projeção muda de nível — transactional outbox, como todo evento do Barrier.
 *
 * <p><b>O agregado do envelope continua sendo a avaliação, não o subject</b> — e isso é escolha, não
 * descuido. Conceitualmente o fato é sobre o cliente, e a lição do {@code tzofe} (partição por
 * documento) aponta para o subject; mas o campo do {@code EventEnvelope} chama-se literalmente
 * {@code assessmentId} e é ele que a Webhook API grava em {@code deliveries.assessment_id}. Pôr um
 * id de subject ali rotularia a coluna errado em silêncio, na trilha de entrega. Trocar a chave de
 * partição é mudança deliberada do contrato do envelope (fila-origem F8), não efeito colateral
 * desta entrega. O {@code subjectId} vai no payload.
 */
@Component
public class RiskLevelChangeEventPublisher {

  static final String EVENT_TYPE = "barrier.subject.risk_level_changed";
  static final int EVENT_VERSION = 1;

  private final OutboxRecorder outbox;
  private final ObjectMapper objectMapper;

  public RiskLevelChangeEventPublisher(OutboxRecorder outbox, ObjectMapper objectMapper) {
    this.outbox = outbox;
    this.objectMapper = objectMapper;
  }

  public void publish(Assessment assessment, RiskLevelTransition transition, String engineVersion) {
    outbox.record(
        assessment.id().asString(),
        EVENT_TYPE,
        EVENT_VERSION,
        objectMapper.writeValueAsString(
            RiskLevelChangedPayload.from(assessment, transition, engineVersion)),
        assessment.correlationId());
  }
}
