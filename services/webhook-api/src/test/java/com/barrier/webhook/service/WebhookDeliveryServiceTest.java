package com.barrier.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.webhook.client.HmacSigner;
import com.barrier.webhook.client.WebhookClient;
import com.barrier.webhook.client.WebhookRequest;
import com.barrier.webhook.client.WebhookSendResult;
import com.barrier.webhook.config.WebhookProperties;
import com.barrier.webhook.domain.Delivery;
import com.barrier.webhook.domain.DeliveryStatus;
import com.barrier.webhook.domain.WebhookEndpoint;
import com.barrier.webhook.repository.DeliveryRepository;
import com.barrier.webhook.repository.WebhookEndpointRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryServiceTest {

  @Mock DeliveryRepository repository;
  @Mock WebhookEndpointRepository endpointRepository;
  @Mock WebhookClient client;

  private final HmacSigner signer = new HmacSigner();

  /** Destino global configurado e nenhum endpoint por tenant: a situação de dev. */
  private WebhookDeliveryService service(String targetUrl) {
    return service(targetUrl, null);
  }

  private WebhookDeliveryService service(String targetUrl, WebhookEndpoint registrado) {
    WebhookProperties properties =
        new WebhookProperties(targetUrl, "secret", 5, Duration.ofSeconds(1));
    if (registrado != null) {
      when(endpointRepository.findByTenantId(registrado.tenantId()))
          .thenReturn(Optional.of(registrado));
    }
    return new WebhookDeliveryService(
        repository,
        new WebhookEndpointService(endpointRepository, properties, Duration.ofHours(24)),
        client,
        signer,
        properties,
        transactionTemplate(),
        Duration.ofMinutes(2));
  }

  /**
   * Executa o callback sem transação de verdade: aqui interessa <b>o que</b> fica dentro da
   * transação (a reivindicação) e o que fica fora (o POST). O comportamento transacional em si é
   * exercitado no teste de integração com Postgres real.
   */
  private static TransactionTemplate transactionTemplate() {
    return new TransactionTemplate(
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {}

          @Override
          public void rollback(TransactionStatus status) {}
        });
  }

  private EventEnvelope event() {
    return EventEnvelope.of("barrier.assessment.completed", "aid", 1, "{\"status\":\"APROVADO\"}");
  }

  @Test
  void entregaComSucessoMarcaDelivered() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);
    when(repository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
    when(client.send(any(WebhookRequest.class))).thenReturn(WebhookSendResult.ok(200));

    service("http://client/webhook").onEvent(event(), "default");

    ArgumentCaptor<Delivery> saved = ArgumentCaptor.forClass(Delivery.class);
    verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(DeliveryStatus.DELIVERED);
  }

  @Test
  void falhaMarcaFailedComRetry() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);
    when(repository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
    when(client.send(any(WebhookRequest.class)))
        .thenReturn(WebhookSendResult.failure(500, "erro"));

    service("http://client/webhook").onEvent(event(), "default");

    ArgumentCaptor<Delivery> saved = ArgumentCaptor.forClass(Delivery.class);
    verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(saved.getValue().nextAttemptAt()).isNotNull();
  }

  @Test
  void eventoDuplicadoNaoEntrega() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(true);

    service("http://client/webhook").onEvent(event(), "default");

    verify(client, never()).send(any());
    verify(repository, never()).save(any());
  }

  /**
   * Regressão: a entrega nasce com {@code next_attempt_at = created_at}, ou seja, já vencida. Como
   * o listener grava a linha e só então faz o POST (até 10s), o scheduler — que roda a cada 5s —
   * encontrava a mesma linha e postava em paralelo, dobrando a entrega numa instância só. Nascer
   * reivindicada é o que fecha essa janela.
   */
  @Test
  void entregaNasceReivindicadaParaOSchedulerNaoCompetirComOListener() {
    Delivery nova = Delivery.create(UUID.randomUUID(), "aid", "default", "http://c/w", "{}");

    assertThat(nova.claimedAt()).isNotNull();
    assertThat(nova.claimedAt()).isEqualTo(nova.createdAt());
  }

  /** Falha libera a posse: quem governa a próxima tentativa é o backoff, não a lease. */
  @Test
  void falhaLiberaAPosse() {
    Delivery d = Delivery.create(UUID.randomUUID(), "aid", "default", "http://c/w", "{}");

    d.markFailed("erro", 5, java.time.Instant.now().plusSeconds(30));

    assertThat(d.claimedAt()).isNull();
    assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED);
  }

  /** O reprocessamento reivindica antes de postar — ler sem posse era o que duplicava a entrega. */
  @Test
  void retryDueReivindicaAsEntregasAntesDeTentar() {
    Delivery pendente = Delivery.create(UUID.randomUUID(), "aid", "default", "http://c/w", "{}");
    when(repository.claimDue(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
        .thenReturn(java.util.List.of(pendente));
    when(repository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
    when(client.send(any(WebhookRequest.class))).thenReturn(WebhookSendResult.ok(200));

    int tentadas = service("http://client/webhook").retryDue();

    assertThat(tentadas).isEqualTo(1);
    verify(repository).claimDue(any(), org.mockito.ArgumentMatchers.anyInt(), any());
  }

  @Test
  void semEndpointConfiguradoNaoEntrega() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);

    service("").onEvent(event(), "default");

    verify(client, never()).send(any());
    verify(repository, never()).save(any());
  }

  /**
   * O núcleo do vazamento cross-tenant: o destino tem que sair do tenant dono do evento. Com
   * destino global, o veredito de KYC dos clientes de uma empresa ia para o endpoint de outra.
   */
  @Test
  void entregaVaiParaOEndpointDoTenantDoEvento() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);
    when(repository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
    when(client.send(any(WebhookRequest.class))).thenReturn(WebhookSendResult.ok(200));
    WebhookEndpoint doTenant = WebhookEndpoint.register("acme", "https://acme.example/webhook");

    service("https://destino-global.example/webhook", doTenant).onEvent(event(), "acme");

    ArgumentCaptor<WebhookRequest> enviado = ArgumentCaptor.forClass(WebhookRequest.class);
    verify(client).send(enviado.capture());
    assertThat(enviado.getValue().url()).isEqualTo("https://acme.example/webhook");
  }

  /** Endpoint desativado não cai no destino global — isso reintroduziria o vazamento. */
  @Test
  void endpointDesativadoNaoEntregaNemCaiNoDestinoGlobal() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);
    WebhookEndpoint desativado =
        WebhookEndpoint.register("acme", "https://acme.example/webhook").deactivate();

    service("https://destino-global.example/webhook", desativado).onEvent(event(), "acme");

    verify(client, never()).send(any());
    verify(repository, never()).save(any());
  }

  /**
   * Segredo compartilhado permitia a um parceiro forjar o callback de KYC de outro: quem conhecesse
   * o segredo assinava um "APROVADO" válido para qualquer tenant. Cada um assina com o seu.
   */
  @Test
  void assinaComOSegredoDoTenantENaoComOGlobal() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);
    when(repository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
    when(client.send(any(WebhookRequest.class))).thenReturn(WebhookSendResult.ok(200));
    WebhookEndpoint acme =
        WebhookEndpoint.register("acme", "https://acme.example/hook", "segredo-da-acme");
    EventEnvelope evento = event();

    service("https://global.example/hook", acme).onEvent(evento, "acme");

    ArgumentCaptor<WebhookRequest> enviado = ArgumentCaptor.forClass(WebhookRequest.class);
    verify(client).send(enviado.capture());
    assertThat(enviado.getValue().signature())
        .isEqualTo(signer.sign(evento.payload(), "segredo-da-acme"))
        .isNotEqualTo(signer.sign(evento.payload(), "secret"));
    assertThat(enviado.getValue().previousSignature()).isNull();
  }

  /** Durante a rotação vão as duas assinaturas: o cliente troca a chave quando puder. */
  @Test
  void duranteARotacaoEnviaTambemAAssinaturaAnterior() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);
    when(repository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
    when(client.send(any(WebhookRequest.class))).thenReturn(WebhookSendResult.ok(200));
    WebhookEndpoint rotacionado =
        WebhookEndpoint.register("acme", "https://acme.example/hook", "segredo-antigo")
            .rotateSecret(Duration.ofHours(1));
    EventEnvelope evento = event();

    service("", rotacionado).onEvent(evento, "acme");

    ArgumentCaptor<WebhookRequest> enviado = ArgumentCaptor.forClass(WebhookRequest.class);
    verify(client).send(enviado.capture());
    assertThat(enviado.getValue().previousSignature())
        .isEqualTo(signer.sign(evento.payload(), "segredo-antigo"));
    assertThat(enviado.getValue().signature())
        .isEqualTo(signer.sign(evento.payload(), rotacionado.secret()))
        .isNotEqualTo(enviado.getValue().previousSignature());
  }

  /** Vencida a janela, o segredo antigo para de ser aceito — senão a rotação não protege de nada. */
  @Test
  void depoisDaJanelaNaoEnviaMaisAAssinaturaAnterior() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);
    when(repository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
    when(client.send(any(WebhookRequest.class))).thenReturn(WebhookSendResult.ok(200));
    WebhookEndpoint expirado =
        WebhookEndpoint.register("acme", "https://acme.example/hook", "segredo-antigo")
            .rotateSecret(Duration.ofSeconds(-1));

    service("", expirado).onEvent(event(), "acme");

    ArgumentCaptor<WebhookRequest> enviado = ArgumentCaptor.forClass(WebhookRequest.class);
    verify(client).send(enviado.capture());
    assertThat(enviado.getValue().previousSignature()).isNull();
  }

  /** Sem tenant no evento não há como endereçar; com o global vazio, nada é entregue. */
  @Test
  void eventoSemTenantENaoEntregueQuandoNaoHaDestinoGlobal() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);

    service("").onEvent(event(), null);

    verify(client, never()).send(any());
  }
}
