package com.barrier.webhook.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebhookEndpointTest {

  @Test
  void registraUrlHttps() {
    WebhookEndpoint endpoint = WebhookEndpoint.register("acme", " https://acme.example/hook ");

    assertThat(endpoint.targetUrl()).isEqualTo("https://acme.example/hook");
    assertThat(endpoint.active()).isTrue();
  }

  /** O payload leva documento, nome e o veredito de PLD-FT: em texto claro, qualquer salto lê. */
  @Test
  void recusaHttpParaHostRemoto() {
    assertThatThrownBy(() -> WebhookEndpoint.register("acme", "http://acme.example/hook"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sem TLS");
  }

  /** Dev precisa apontar para o próprio host; só aí http é aceito. */
  @Test
  void aceitaHttpEmHostLocal() {
    assertThat(WebhookEndpoint.register("acme", "http://localhost:9099/hook").targetUrl())
        .isEqualTo("http://localhost:9099/hook");
  }

  @Test
  void recusaEsquemaQueNaoSejaHttp() {
    assertThatThrownBy(() -> WebhookEndpoint.register("acme", "ftp://acme.example/hook"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WebhookEndpoint.register("acme", "/apenas/um/caminho"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recusaTenantOuUrlVazios() {
    assertThatThrownBy(() -> WebhookEndpoint.register(" ", "https://acme.example/hook"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WebhookEndpoint.register("acme", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void registroNasceComSegredoProprio() {
    WebhookEndpoint a = WebhookEndpoint.register("acme", "https://acme.example/hook");
    WebhookEndpoint b = WebhookEndpoint.register("outra", "https://outra.example/hook");

    assertThat(a.secret()).isNotBlank().hasSizeGreaterThanOrEqualTo(40);
    assertThat(a.secret()).isNotEqualTo(b.secret());
    assertThat(a.previousSecret()).isNull();
  }

  /** Atualizar a URL não pode trocar o segredo: derrubaria a verificação do cliente sem pedido. */
  @Test
  void registroComSegredoExistentePreservaOSegredo() {
    WebhookEndpoint atualizado =
        WebhookEndpoint.register("acme", "https://acme.example/novo", "segredo-em-uso");

    assertThat(atualizado.secret()).isEqualTo("segredo-em-uso");
  }

  @Test
  void rotacaoMantemOAnteriorValidoPelaJanela() {
    WebhookEndpoint original =
        WebhookEndpoint.register("acme", "https://acme.example/hook", "segredo-antigo");

    WebhookEndpoint rotacionado = original.rotateSecret(java.time.Duration.ofHours(24));

    assertThat(rotacionado.secret()).isNotEqualTo("segredo-antigo");
    assertThat(rotacionado.previousSecret()).isEqualTo("segredo-antigo");
    assertThat(rotacionado.usablePreviousSecret()).isEqualTo("segredo-antigo");
    assertThat(rotacionado.previousSecretUntil()).isAfter(java.time.Instant.now());
  }

  @Test
  void segredoAnteriorDeixaDeValerDepoisDaJanela() {
    WebhookEndpoint expirado =
        WebhookEndpoint.register("acme", "https://acme.example/hook", "segredo-antigo")
            .rotateSecret(java.time.Duration.ofSeconds(-1));

    assertThat(expirado.previousSecret()).isEqualTo("segredo-antigo");
    assertThat(expirado.usablePreviousSecret()).isNull();
  }

  @Test
  void desativarPreservaOCadastro() {
    WebhookEndpoint desativado =
        WebhookEndpoint.register("acme", "https://acme.example/hook").deactivate();

    assertThat(desativado.active()).isFalse();
    assertThat(desativado.targetUrl()).isEqualTo("https://acme.example/hook");
  }
}
