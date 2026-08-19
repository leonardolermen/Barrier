package com.barrier.webhook.service;

import com.barrier.commons.jobs.SingletonJobLock;
import com.barrier.commons.event.EventEnvelope;
import com.barrier.commons.observability.Correlation;
import com.barrier.webhook.repository.DeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reconciliação: relê o tópico de decisões concluídas numa janela recente e cria a entrega de todo
 * evento que não tem uma.
 *
 * <p>Sem isto, o tratamento de erro do consumo é incompleto. Retry com backoff cobre a falha curta,
 * e a DLT evita que uma mensagem trave a partição — mas o que foi para a DLT continua sendo uma
 * decisão de KYC que o cliente nunca recebeu, e ninguém volta lá. Este job é o que fecha essa
 * lacuna, e também cobre a janela em que o consumidor esteve fora do ar por mais tempo que o
 * backoff.
 *
 * <p>A fonte de verdade é o próprio tópico, não uma consulta ao banco da risk-engine: as
 * {@code assessments} vivem no schema de outro serviço, e ler de lá trocaria uma lacuna de entrega
 * por um acoplamento entre schemas. O limite disso é a retenção do Kafka — a janela precisa caber
 * dentro dela.
 *
 * <p>Roda com um consumidor <b>avulso</b> (assign, sem group management e sem commit): não
 * interfere no offset do consumidor normal, e duas instâncias reconciliando ao mesmo tempo não se
 * atrapalham, porque a criação da entrega é idempotente por {@code eventId}.
 */
@Component
public class DeliveryReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(DeliveryReconciliationJob.class);
  private static final String TOPIC = "barrier.assessment.completed";
  private static final Duration POLL = Duration.ofSeconds(2);
  private static final int MAX_POLLS = 200;

  private final ConsumerFactory<String, String> consumerFactory;
  private final DeliveryRepository repository;
  private final WebhookDeliveryService deliveryService;
  private final ObjectMapper objectMapper;
  private final Duration window;
  private final SingletonJobLock jobLock;

  public DeliveryReconciliationJob(
      ConsumerFactory<String, String> consumerFactory,
      DeliveryRepository repository,
      WebhookDeliveryService deliveryService,
      ObjectMapper objectMapper,
      SingletonJobLock jobLock,
      @Value("${barrier.webhook.reconciliation.window:PT6H}") Duration window) {
    this.consumerFactory = consumerFactory;
    this.repository = repository;
    this.deliveryService = deliveryService;
    this.objectMapper = objectMapper;
    this.jobLock = jobLock;
    this.window = window;
  }

  /**
   * Reconcilia <b>uma vez no cluster</b>.
   *
   * <p>Sem o lock, as 5 réplicas abriam 5 consumidores avulsos varrendo a mesma janela de 6 horas
   * do tópico, no mesmo instante — trabalho multiplicado por cinco sobre o broker e sobre o banco.
   * A entrega em si é idempotente por {@code eventId}, então não haveria duplicata; o desperdício é
   * que era real, e ele cai justamente sobre o mecanismo que existe para funcionar quando o resto
   * já falhou.
   *
   * <p>Piso de 10 minutos: menor que o ciclo de 15, para não pular uma janela legítima, e grande o
   * bastante para uma réplica atrasada não revarrer o que acabou de ser varrido.
   */
  @Scheduled(cron = "${barrier.webhook.reconciliation.cron:0 */15 * * * *}")
  public void reconcile() {
    jobLock.runIfLeader(
        "delivery-reconciliation",
        Duration.ofMinutes(10),
        Duration.ofMinutes(30),
        this::reconcileOnce);
  }

  private void reconcileOnce() {
    int recuperados = reconcileSince(Instant.now().minus(window));
    if (recuperados > 0) {
      log.warn(
          "Reconciliação recuperou {} decisão(ões) sem entrega registrada na última janela de {}",
          recuperados,
          window);
    }
  }

  /** Relê a partir de {@code since} e devolve quantas entregas faltavam. Visível para teste. */
  public int reconcileSince(Instant since) {
    try (Consumer<String, String> consumer =
        consumerFactory.createConsumer("webhook-reconciler", "-recon")) {
      List<TopicPartition> partitions = partitions(consumer);
      if (partitions.isEmpty()) {
        return 0;
      }
      consumer.assign(partitions);
      Map<TopicPartition, Long> fim = consumer.endOffsets(partitions);
      posiciona(consumer, partitions, since);

      int recuperados = 0;
      for (int i = 0; i < MAX_POLLS && !chegouAoFim(consumer, partitions, fim); i++) {
        ConsumerRecords<String, String> records = consumer.poll(POLL);
        if (records.isEmpty()) {
          break;
        }
        for (ConsumerRecord<String, String> record : records) {
          recuperados += recuperar(record) ? 1 : 0;
        }
      }
      return recuperados;
    } catch (RuntimeException e) {
      // Reconciliação que falha não pode derrubar o serviço nem o scheduler: ela roda de novo.
      log.error("Falha na reconciliação de entregas", e);
      return 0;
    }
  }

  private List<TopicPartition> partitions(Consumer<String, String> consumer) {
    List<PartitionInfo> info = consumer.partitionsFor(TOPIC);
    if (info == null) {
      return List.of();
    }
    return info.stream().map(p -> new TopicPartition(TOPIC, p.partition())).toList();
  }

  private void posiciona(
      Consumer<String, String> consumer, List<TopicPartition> partitions, Instant since) {
    Map<TopicPartition, Long> alvo = new HashMap<>();
    partitions.forEach(p -> alvo.put(p, since.toEpochMilli()));
    Map<TopicPartition, OffsetAndTimestamp> porTempo = consumer.offsetsForTimes(alvo);
    partitions.forEach(
        p -> {
          OffsetAndTimestamp offset = porTempo.get(p);
          if (offset == null) {
            // Nada dentro da janela nesta partição: só o que vier daqui para a frente interessa.
            consumer.seekToEnd(List.of(p));
          } else {
            consumer.seek(p, offset.offset());
          }
        });
  }

  private boolean chegouAoFim(
      Consumer<String, String> consumer, List<TopicPartition> partitions, Map<TopicPartition, Long> fim) {
    return partitions.stream()
        .allMatch(p -> consumer.position(p) >= fim.getOrDefault(p, 0L));
  }

  /** @return true se o evento não tinha entrega e foi reprocessado */
  private boolean recuperar(ConsumerRecord<String, String> record) {
    EventEnvelope envelope;
    String tenantId;
    try {
      envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = objectMapper.readValue(envelope.payload(), Map.class);
      Object tenant = payload.get("tenantId");
      tenantId = tenant == null ? null : tenant.toString();
    } catch (RuntimeException e) {
      // Malformado continua malformado; já está registrado na DLT pelo consumo normal.
      log.warn("Evento ilegível ignorado na reconciliação (offset {})", record.offset());
      return false;
    }
    if (repository.existsByEventId(envelope.eventId())) {
      return false;
    }
    log.warn(
        "Decisão {} (evento {}) não tinha entrega registrada; reprocessando",
        envelope.assessmentId(),
        envelope.eventId());
    Correlation.run(envelope.correlationId(), () -> deliveryService.onEvent(envelope, tenantId));
    return true;
  }
}
