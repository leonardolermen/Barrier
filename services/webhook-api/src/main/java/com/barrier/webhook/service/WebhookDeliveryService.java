package com.barrier.webhook.service;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.webhook.client.HmacSigner;
import com.barrier.webhook.client.WebhookClient;
import com.barrier.webhook.client.WebhookRequest;
import com.barrier.webhook.client.WebhookSendResult;
import com.barrier.webhook.config.WebhookProperties;
import com.barrier.webhook.domain.Delivery;
import com.barrier.webhook.domain.SigningMaterial;
import com.barrier.webhook.repository.DeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Entrega os resultados de avaliação ao endpoint do cliente.
 *
 * <p>Idempotência por {@code eventId} (Kafka é at-least-once). A entrega é assinada com HMAC;
 * falhas reagendam com backoff exponencial até esgotar as tentativas.
 */
@Service
public class WebhookDeliveryService {

  private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
  private static final int RETRY_BATCH = 100;

  private final DeliveryRepository repository;
  private final WebhookEndpointService endpoints;
  private final WebhookClient client;
  private final HmacSigner signer;
  private final WebhookProperties properties;
  private final TransactionTemplate transactionTemplate;
  private final Duration lease;
  private final ObjectMapper objectMapper;

  /** Virtual threads: a tarefa fica bloqueada esperando o destino, nao uma thread de plataforma. */
  private final ExecutorService entregas = Executors.newVirtualThreadPerTaskExecutor();

  private final Semaphore permissoes;

  public WebhookDeliveryService(
      DeliveryRepository repository,
      WebhookEndpointService endpoints,
      WebhookClient client,
      HmacSigner signer,
      WebhookProperties properties,
      TransactionTemplate transactionTemplate,
      ObjectMapper objectMapper,
      @Value("${barrier.webhook.workers:3}") int workers,
      @Value("${barrier.webhook.lease:PT2M}") Duration lease) {
    this.repository = repository;
    this.endpoints = endpoints;
    this.client = client;
    this.signer = signer;
    this.properties = properties;
    this.transactionTemplate = transactionTemplate;
    this.objectMapper = objectMapper;
    this.permissoes = new Semaphore(workers);
    // Precisa ser maior que o pior caso de uma tentativa (connect + read timeout do cliente);
    // curta demais faz outro worker reenviar enquanto o POST original ainda está em voo.
    this.lease = lease;
  }

  /** Recebe um evento de avaliação concluída e tenta entregá-lo, no escopo de um tenant. */
  public void onEvent(EventEnvelope envelope, String tenantId) {
    if (repository.existsByEventId(envelope.eventId())) {
      log.debug("Evento {} já registrado; ignorando (idempotência)", envelope.eventId());
      return;
    }
    // O destino sai do tenant DONO DO EVENTO, não de uma configuração global: com destino global,
    // o callback de KYC de um tenant chegava no endpoint do outro.
    String targetUrl = endpoints.resolveTargetUrl(tenantId).orElse(null);
    if (targetUrl == null) {
      log.error(
          "Tenant {} sem endpoint de webhook; evento {} não entregue (a decisão segue disponível"
              + " no GET /v1/assessments)",
          tenantId,
          envelope.eventId());
      return;
    }

    Delivery delivery;
    try {
      delivery =
          repository.save(
              Delivery.create(
                  envelope.eventId(),
                  envelope.assessmentId(),
                  tenantId,
                  targetUrl,
                  envelope.payload(),
                  partitionKeyDe(envelope.payload())));
    } catch (DataIntegrityViolationException e) {
      log.debug("Entrega concorrente para o evento {}; ignorando", envelope.eventId());
      return;
    }
    // A entrega NÃO acontece aqui, de propósito: este método roda na thread do listener Kafka, e um
    // destino que aceita a conexão e demora seguraria o consumo daquela partição — o parceiro lento
    // atrasaria todos que a compartilham, não só a si mesmo. O timeout mitiga, não resolve.
    //
    // Quem entrega é o retryDue(), pelo pool. A latência do parceiro deixa de existir no caminho do
    // broker; o custo é a primeira tentativa esperar um ciclo do scheduler.
    log.debug("Entrega {} registrada; sera enviada pelo pool", delivery.eventId());
  }

  /**
   * Reprocessa entregas vencidas (agendado). Retorna quantas foram tentadas.
   *
   * <p>A reivindicação acontece numa transação curta; os POSTs ficam <b>fora</b> dela. Antes o
   * método só lia (`findDue`) e saía postando: réplicas concorrentes entregavam o mesmo veredito de
   * KYC ao cliente, e mesmo com uma instância só o scheduler competia com o próprio listener do
   * Kafka por entregas recém-criadas (ver migration V003).
   */
  public int retryDue() {
    List<Delivery> due =
        transactionTemplate.execute(
            status -> repository.claimDue(Instant.now(), RETRY_BATCH, lease));
    if (due == null || due.isEmpty()) {
      return 0;
    }
    var tarefas =
        due.stream()
            .map(d -> CompletableFuture.runAsync(() -> comPermissao(() -> attempt(d)), entregas))
            .toList();
    // Espera o lote: sem isto o ciclo seguinte reivindicaria com o anterior ainda em voo, e a
    // concorrência real deixaria de ser a que o teto declara.
    CompletableFuture.allOf(tarefas.toArray(CompletableFuture[]::new)).join();
    return due.size();
  }

  /**
   * O semáforo é o teto de entregas simultâneas — e, por consequência, o que protege o pool de
   * conexões e o parceiro de receber uma rajada.
   *
   * <p>Virtual thread não cria conexão de banco nem paciência no destino: sem este limite, o lote
   * inteiro (100) sairia de uma vez sobre um pool de 5 conexões. <b>O limite é a feature</b> —
   * {@code newVirtualThreadPerTaskExecutor()} sozinho não tem nenhum.
   */
  private void comPermissao(Runnable tarefa) {
    permissoes.acquireUninterruptibly();
    try {
      tarefa.run();
    } finally {
      permissoes.release();
    }
  }

  /**
   * Chave de ordenação a partir do payload.
   *
   * <p>Não sai do envelope: o {@code assessmentId} dele é o id do <b>agregado</b> do evento, e dois
   * eventos sobre o mesmo cliente (a decisão e a mudança de nível de risco) têm agregados
   * diferentes. Ordenar por ele não ordenaria nada.
   *
   * <p>Payload sem subject — ou ilegível — devolve {@code null}: <b>sem ordem exigida é melhor que
   * entrega bloqueada</b>. Payload ilegível já tem tratamento próprio no consumo
   * ({@code MalformedEventException} → DLT); não é aqui que ele deve falhar.
   */
  private String partitionKeyDe(String payload) {
    try {
      Map<String, Object> corpo = objectMapper.readValue(payload, Map.class);
      Object subject = corpo.get("subjectId");
      return subject == null ? null : subject.toString();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private void attempt(Delivery delivery) {
    // Segredo do tenant, resolvido a cada tentativa: uma rotação entre a primeira tentativa e o
    // retry assina com o que vale agora, sem carregar o segredo antigo na linha da entrega.
    SigningMaterial material = endpoints.resolveSigningMaterial(delivery.tenantId());
    // Instante da TENTATIVA, e o mesmo para as duas assinaturas: durante a rotacao o receptor
    // compara a que ele consegue calcular, e dois instantes diferentes fariam uma delas nao bater.
    Instant assinadoEm = Instant.now();
    String signature = signer.sign(delivery.payload(), material.secret(), assinadoEm);
    String previousSignature =
        material.hasPrevious()
            ? signer.sign(delivery.payload(), material.previousSecret(), assinadoEm)
            : null;
    WebhookSendResult result =
        client.send(
            new WebhookRequest(
                delivery.targetUrl(),
                delivery.payload(),
                delivery.eventId().toString(),
                signature,
                previousSignature));

    if (result.success()) {
      delivery.markDelivered();
      log.info("Webhook do evento {} entregue ({})", delivery.eventId(), result.statusCode());
    } else {
      delivery.markFailed(result.detail(), properties.maxAttempts(), nextAttempt(delivery.attempts()));
      log.warn(
          "Falha ao entregar evento {} (tentativa {}): {}",
          delivery.eventId(),
          delivery.attempts() + 1,
          result.detail());
    }
    repository.save(delivery);
  }

  private Instant nextAttempt(int attempts) {
    long factor = 1L << Math.min(attempts, 6); // backoff exponencial, teto no 64x
    return Instant.now().plus(properties.baseBackoff().multipliedBy(factor));
  }
}
