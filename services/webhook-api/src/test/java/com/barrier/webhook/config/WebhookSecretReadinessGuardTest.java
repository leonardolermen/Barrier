package com.barrier.webhook.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class WebhookSecretReadinessGuardTest {

  private static final String SEGREDO_FORTE = "segredo-hmac-aleatorio-com-tamanho-ok-123";

  @Test
  void falhaAoSubirEmProducaoComODefaultDeDesenvolvimento() {
    assertThatThrownBy(() -> guard("dev-secret", prod()).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dev-secret");
  }

  @Test
  void falhaAoSubirEmProducaoSemSegredo() {
    assertThatThrownBy(() -> guard("", prod()).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("WEBHOOK_SECRET");
  }

  @Test
  void falhaAoSubirEmProducaoComSegredoCurto() {
    assertThatThrownBy(() -> guard("curto", prod()).run(null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void naoFalhaEmProducaoComSegredoForte() {
    assertThatCode(() -> guard(SEGREDO_FORTE, prod()).run(null)).doesNotThrowAnyException();
  }

  @Test
  void naoFalhaForaDeProducaoComODefaultDeDesenvolvimento() {
    MockEnvironment dev = new MockEnvironment();
    dev.setActiveProfiles("dev");

    assertThatCode(() -> guard("dev-secret", dev).run(null)).doesNotThrowAnyException();
  }

  private static WebhookSecretReadinessGuard guard(String secret, MockEnvironment environment) {
    return new WebhookSecretReadinessGuard(
        new WebhookProperties("https://cliente/webhook", secret, 5, Duration.ofSeconds(30)),
        environment);
  }

  private static MockEnvironment prod() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    return prod;
  }
}
