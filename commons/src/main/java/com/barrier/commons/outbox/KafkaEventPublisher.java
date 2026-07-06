package com.barrier.commons.outbox;

import com.barrier.commons.event.EventEnvelope;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Adapter que publica eventos no Kafka. Tópico = tipo do evento; chave = assessmentId
 * (garante ordem por avaliação). O valor é o envelope serializado em JSON.
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public void publish(EventEnvelope envelope) {
    String value = objectMapper.writeValueAsString(envelope);
    // join() torna a publicação síncrona: só marcamos SENT após confirmação do broker.
    kafkaTemplate.send(envelope.type(), envelope.assessmentId(), value).join();
  }
}
