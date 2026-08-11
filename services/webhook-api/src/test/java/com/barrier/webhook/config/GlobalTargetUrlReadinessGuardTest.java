package com.barrier.webhook.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class GlobalTargetUrlReadinessGuardTest {

  private static GlobalTargetUrlReadinessGuard guard(String targetUrl, String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return new GlobalTargetUrlReadinessGuard(
        new WebhookProperties(targetUrl, "secret", 5, Duration.ofSeconds(1)), environment);
  }

  /** Destino único em produção = callback de KYC de um tenant no endpoint de outro. */
  @Test
  void prodComDestinoGlobalNaoSobe() {
    assertThatThrownBy(() -> guard("https://global.example/hook", "prod").run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("target-url");
  }

  @Test
  void prodSemDestinoGlobalSobe() {
    assertThatCode(() -> guard("", "prod").run(null)).doesNotThrowAnyException();
    assertThatCode(() -> guard(null, "prod").run(null)).doesNotThrowAnyException();
  }

  @Test
  void foraDeProdApenasAvisa() {
    assertThatCode(() -> guard("https://global.example/hook").run(null)).doesNotThrowAnyException();
  }
}
