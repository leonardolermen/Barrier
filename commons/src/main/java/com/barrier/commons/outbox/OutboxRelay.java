package com.barrier.commons.outbox;

import com.barrier.commons.event.EventEnvelope;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publica periodicamente os eventos pendentes da outbox no Kafka e os marca como enviados.
 *
 * <p>Idempotência de publicação: falha ao publicar mantém o evento PENDING para nova
 * tentativa; a marcação como SENT só ocorre após confirmação do broker.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final Limit BATCH = Limit.of(100);

  private final OutboxRepository repository;
  private final EventPublisher publisher;

  public OutboxRelay(OutboxRepository repository, EventPublisher publisher) {
    this.repository = repository;
    this.publisher = publisher;
  }

  /** Executado periodicamente. Também pode ser chamado diretamente (ex.: em testes). */
  @Scheduled(fixedDelayString = "${barrier.outbox.relay-delay-ms:2000}")
  @Transactional
  public int publishPending() {
    List<OutboxEvent> pending =
        repository.findByStatusOrderByOccurredAtAsc(OutboxStatus.PENDING, BATCH);
    int sent = 0;
    for (OutboxEvent event : pending) {
      try {
        publisher.publish(toEnvelope(event));
        event.markSent();
        sent++;
      } catch (RuntimeException e) {
        event.markFailedAttempt();
        log.warn(
            "Falha ao publicar evento {} (tentativa {})", event.getId(), event.getAttempts(), e);
      }
    }
    return sent;
  }

  private EventEnvelope toEnvelope(OutboxEvent event) {
    return new EventEnvelope(
        event.getId(),
        event.getType(),
        event.getAggregateId(),
        event.getOccurredAt(),
        event.getVersion(),
        event.getPayload());
  }
}
