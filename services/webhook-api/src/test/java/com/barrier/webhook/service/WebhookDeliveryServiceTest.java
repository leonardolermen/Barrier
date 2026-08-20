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
        new tools.jackson.databind.ObjectMapper(),
        3,
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

  /**
   * Grava e entrega, que é o caminho real desde que o listener parou de entregar inline.
   *
   * <p>Antes bastava {@code onEvent} — ele fazia o POST na própria thread. Agora {@code onEvent} só
   * persiste, e quem entrega é o {@code retryDue()} pelo pool. Os testes que afirmam algo sobre a
   * ENTREGA (assinatura, status, rotação de segredo) precisam das duas etapas; os que afirmam algo
   * sobre a GRAVAÇÃO continuam usando só {@code onEvent}.
   */
  private void gravaEEntrega(WebhookDeliveryService service, EventEnvelope evento, String tenant) {
    service.onEvent(evento, tenant);

    ArgumentCaptor<Delivery> criada = ArgumentCaptor.forClass(Delivery.class);
    verify(repository).save(criada.capture());
    when(repository.claimDue(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
        .thenReturn(java.util.List.of(criada.getValue()));

    service.retryDue();
  }

  private EventEnvelope event() {
    return EventEnvelope.of("barrier.assessment.completed", "aid", 1, "{\"status\":\"APROVADO\"}");
  }

  @Test
  void entregaComSucessoMarcaDelivered() {
    when(repository.existsByEventId(any(UUID.class))).thenReturn(false);
    when(repository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
    when(client.send(any(WebhookRequest.class))).thenReturn(WebhookSendResult.ok(200));

    gravaEEntrega(service("http://client/webhook"), event(), "default");

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

    gravaEEntrega(service("http://client/webhook"), event(), "default");

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
   * A entrega nasce <b>livre</b>, e isto é a inversão de uma regra anterior — a mudança de premissa
   * está registrada porque o motivo antigo era legítimo.
   *
   * <p><b>Antes:</b> nascia reivindicada. A entrega nasce com {@code next_attempt_at = created_at},
   * já vencida; como o listener gravava a linha e <i>só então</i> fazia o POST (até 10s), o
   * scheduler encontrava a mesma linha e postava em paralelo — entrega dobrada numa instância só.
   * Nascer presa fechava essa janela.
   *
   * <p><b>Agora:</b> o listener não entrega mais (ver {@link #gravarNaoEntregaNaThreadDoListener}).
   * Não há com quem competir — e nascer presa deixaria a entrega parada até o lease de 2 minutos
   * vencer, transformando toda primeira entrega numa espera de minutos.
   */
  @Test
  void entregaNasceLivreParaOSchedulerPoderPegaLa() {
    Delivery nova =
        Delivery.create(UUID.randomUUID(), "aid", "default", "http://c/w", "{}", "subject-1");

    assertThat(nova.claimedAt())
        .as("nasceu reivindicada e ficaria parada ate o lease vencer")
        .isNull();
  }

  /**
   * O POST não pode rodar na thread do listener Kafka.
   *
   * <p>Um destino que aceita a conexão e demora segura o consumo da partição inteira: o parceiro
   * lento atrasa <b>todos</b> que compartilham a partição, não só a si mesmo. O timeout mitiga, não
   * resolve. Achado da auditoria (P2).
   */
  @Test
  void gravarNaoEntregaNaThreadDoListener() {
    when(repository.existsByEventId(any())).thenReturn(false);
    when(repository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));

    service("http://client/webhook").onEvent(event(), "default");

    verify(client, org.mockito.Mockito.never()).send(any(WebhookRequest.class));
  }

  /** Falha libera a posse: quem governa a próxima tentativa é o backoff, não a lease. */
  @Test
  void falhaLiberaAPosse() {
    Delivery d = Delivery.create(UUID.randomUUID(), "aid", "default", "http://c/w", "{}", "subject-1");

    d.markFailed("erro", 5, java.time.Instant.now().plusSeconds(30));

    assertThat(d.claimedAt()).isNull();
    assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED);
  }

  /** O reprocessamento reivindica antes de postar — ler sem posse era o que duplicava a entrega. */
  @Test
  void retryDueReivindicaAsEntregasAntesDeTentar() {
    Delivery pendente = Delivery.create(UUID.randomUUID(), "aid", "default", "http://c/w", "{}", "subject-1");
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

    gravaEEntrega(service("https://destino-global.example/webhook", doTenant), event(), "acme");

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

    gravaEEntrega(service("https://global.example/hook", acme), evento, "acme");

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

    gravaEEntrega(service("", rotacionado), evento, "acme");

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

    gravaEEntrega(service("", expirado), event(), "acme");

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
