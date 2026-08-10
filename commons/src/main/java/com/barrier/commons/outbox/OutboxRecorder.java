package com.barrier.commons.outbox;

import com.barrier.commons.observability.Correlation;
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
    record(aggregateId, type, version, payload, Correlation.current());
  }

  /**
   * Grava o evento carregando um id de correlação explícito.
   *
   * <p>Necessário porque o produtor mais importante — a conclusão de uma avaliação — roda num
   * {@code @Scheduled}, longe do MDC da requisição original: o id vem do agregado, não do contexto
   * da thread.
   */
  public void record(
      String aggregateId, String type, int version, String payload, String correlationId) {
    repository.save(
        OutboxEvent.pending(aggregateId, type, payload, version, Instant.now(), correlationId));
  }
}
