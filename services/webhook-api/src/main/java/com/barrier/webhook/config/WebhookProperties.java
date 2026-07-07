package com.barrier.webhook.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração da entrega de webhooks.
 *
 * @param targetUrl endpoint do cliente que recebe o callback (config simples por ora; um
 *     registro por cliente é evolução futura)
 * @param secret segredo compartilhado para a assinatura HMAC
 * @param maxAttempts número máximo de tentativas antes de marcar a entrega como morta
 * @param baseBackoff atraso base do backoff exponencial entre tentativas
 */
@ConfigurationProperties(prefix = "barrier.webhook")
public record WebhookProperties(
    String targetUrl, String secret, int maxAttempts, Duration baseBackoff) {

  public WebhookProperties {
    if (maxAttempts <= 0) {
      maxAttempts = 5;
    }
    if (baseBackoff == null) {
      baseBackoff = Duration.ofSeconds(30);
    }
  }
}
