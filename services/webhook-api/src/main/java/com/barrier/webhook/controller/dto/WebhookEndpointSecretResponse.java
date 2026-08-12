package com.barrier.webhook.controller.dto;

import com.barrier.webhook.domain.WebhookEndpoint;
import java.time.Instant;

/**
 * Resposta que <b>expõe o segredo</b> — só no registro e na rotação, nunca no {@code GET}.
 *
 * <p>Mesmo desenho da emissão de API key da risk-engine: o valor aparece uma vez, no momento em que
 * quem opera precisa copiá-lo para o parceiro. Devolvê-lo em toda consulta transformaria qualquer
 * leitura do registro em vazamento do que permite forjar callbacks.
 *
 * @param previousSecretUntil até quando o segredo anterior continua sendo aceito; {@code null} num
 *     registro novo
 */
public record WebhookEndpointSecretResponse(
    String tenantId,
    String targetUrl,
    String secret,
    Instant previousSecretUntil,
    boolean active,
    Instant updatedAt,
    String warning) {

  private static final String AVISO =
      "Guarde agora e configure no parceiro: este valor não é recuperável.";

  public static WebhookEndpointSecretResponse from(WebhookEndpoint endpoint) {
    return new WebhookEndpointSecretResponse(
        endpoint.tenantId(),
        endpoint.targetUrl(),
        endpoint.secret(),
        endpoint.previousSecretUntil(),
        endpoint.active(),
        endpoint.updatedAt(),
        AVISO);
  }
}
