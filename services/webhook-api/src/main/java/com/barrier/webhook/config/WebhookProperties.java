package com.barrier.webhook.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração da entrega de webhooks.
 *
 * @param targetUrl destino <b>único</b> para todos os tenants — fallback de desenvolvimento. O
 *     destino real vem do registro por tenant ({@code webhook_endpoints}); em produção esta
 *     propriedade é proibida ({@code GlobalTargetUrlReadinessGuard}), porque entrega o callback de
 *     um cliente no endpoint de outro
 * @param secret segredo compartilhado para a assinatura HMAC — ainda um só para todos os tenants
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
