package com.barrier.commons.outbox;

import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Ponto de entrada para os serviços registrarem um evento de domínio.
 *
 * <p>Deve ser chamado DENTRO da mesma transação que altera o estado do agregado, garantindo
 * atomicidade entre "gravou estado" e "publicou evento" (transactional outbox).
 */
@Component
public class OutboxRecorder {

  private final OutboxRepository repository;

  public OutboxRecorder(OutboxRepository repository) {
    this.repository = repository;
  }

  /**
   * Grava um evento pendente na outbox.
   *
   * @param aggregateId id de correlação (ex.: assessmentId), também usado como chave no Kafka
   * @param type nome canônico do evento (ex.: {@code barrier.assessment.completed})
   * @param version versão do contrato do payload
   * @param payload conteúdo já serializado (JSON)
   */
  public void record(String aggregateId, String type, int version, String payload) {
    repository.save(OutboxEvent.pending(aggregateId, type, payload, version, Instant.now()));
  }
}
