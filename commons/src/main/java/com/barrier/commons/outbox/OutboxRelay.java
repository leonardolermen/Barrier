package com.barrier.commons.outbox;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.commons.observability.Correlation;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publica periodicamente os eventos pendentes da outbox no Kafka e os marca como enviados.
 *
 * <p><b>Forma do processamento</b>, e por que ela é assim — a mesma do {@code AssessmentProcessor}:
 *
 * <ol>
 *   <li><b>reivindica</b> um lote em transação curta ({@code FOR UPDATE SKIP LOCKED} + lease);
 *   <li><b>publica cada evento</b> no Kafka <i>fora</i> de transação;
 *   <li><b>marca o desfecho</b> de cada um em sua própria transação.
 * </ol>
 *
 * <p>Antes, o método inteiro era {@code @Transactional} e o {@code kafkaTemplate.send(...).join()}
 * rodava dentro dele, segurando o lock de até 100 linhas enquanto esperava o broker confirmar. Com
 * o Kafka em rebalance, uma conexão do pool ficava presa e as 100 linhas travadas — exatamente o
 * anti-padrão que o {@code AssessmentProcessor} documenta como causa de incidente e que tinha sido
 * corrigido só do lado das avaliações.
 *
 * <p>Idempotência de publicação: falha mantém o evento PENDING e libera a posse, para nova
 * tentativa no ciclo seguinte; a marcação como SENT só ocorre após confirmação do broker. Kafka é
 * at-least-once por construção — um evento publicado cuja marcação não commitou será republicado, e
 * o consumidor deduplica por {@code eventId}.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final int BATCH = 100;

  private final OutboxRepository repository;
  private final EventPublisher publisher;
  private final TransactionTemplate transactionTemplate;
  private final Duration lease;

  public OutboxRelay(
      OutboxRepository repository,
      EventPublisher publisher,
      TransactionTemplate transactionTemplate,
      @Value("${barrier.outbox.lease:PT1M}") Duration lease) {
    this.repository = repository;
    this.publisher = publisher;
    this.transactionTemplate = transactionTemplate;
    this.lease = lease;
  }

  /** Executado periodicamente. Também pode ser chamado diretamente (ex.: em testes). */
  @Scheduled(fixedDelayString = "${barrier.outbox.relay-delay-ms:2000}")
  public int publishPending() {
    List<Claimed> claimed =
        transactionTemplate.execute(
            status ->
                repository.claimPending(BATCH, lease).stream().map(Claimed::from).toList());
    if (claimed == null || claimed.isEmpty()) {
      return 0;
    }

    int sent = 0;
    for (Claimed event : claimed) {
      if (publishOne(event)) {
        sent++;
      }
    }
    return sent;
  }

  /**
   * Um evento falha sozinho: uma exceção publicando o terceiro do lote não pode desfazer a marcação
   * dos dois primeiros, que já chegaram ao broker — seriam republicados sem necessidade.
   */
  private boolean publishOne(Claimed event) {
    AtomicBoolean published = new AtomicBoolean(false);
    Correlation.run(event.envelope().correlationId(), () -> published.set(doPublish(event)));
    return published.get();
  }

  private boolean doPublish(Claimed event) {
    try {
      publisher.publish(event.envelope());
    } catch (RuntimeException e) {
      transactionTemplate.executeWithoutResult(
          status -> repository.findById(event.id()).ifPresent(OutboxEvent::markFailedAttempt));
      log.warn("Falha ao publicar evento {}; será tentado de novo", event.id(), e);
      return false;
    }
    transactionTemplate.executeWithoutResult(
        status -> repository.findById(event.id()).ifPresent(OutboxEvent::markSent));
    return true;
  }

  /**
   * Snapshot do que é preciso para publicar, tirado ainda dentro da transação de reivindicação.
   * A entidade não atravessa a fronteira: fora da transação ela estaria desanexada, e ler dela
   * seria depender de estado que ninguém mais está guardando.
   */
  private record Claimed(UUID id, EventEnvelope envelope) {

    static Claimed from(OutboxEvent event) {
      return new Claimed(
          event.getId(),
          new EventEnvelope(
              event.getId(),
              event.getType(),
              event.getAggregateId(),
              event.getOccurredAt(),
              event.getVersion(),
              event.getPayload(),
              event.getCorrelationId()));
    }
  }
}
