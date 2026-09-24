package com.barrier.webhook.controller.dto;

import com.barrier.webhookdelivery.domain.WebhookEndpoint;
import java.time.Instant;

/**
 * Representação do endpoint registrado, <b>sem o segredo</b> — ele só aparece no registro e na
 * rotação ({@link WebhookEndpointSecretResponse}).
 *
 * @param secretConfigured se o tenant tem segredo próprio. Sempre {@code true} depois da V009 (que
 *     gerou um segredo por endpoint para as linhas que ainda dependiam do segredo global de dev);
 *     não há mais segredo global para cair de volta
 * @param previousSecretUntil até quando a janela de rotação vale; {@code null} fora dela
 */
public record WebhookEndpointResponse(
    String tenantId,
    String targetUrl,
    boolean active,
    boolean secretConfigured,
    Instant previousSecretUntil,
    Instant createdAt,
    Instant updatedAt) {

  public static WebhookEndpointResponse from(WebhookEndpoint endpoint) {
    return new WebhookEndpointResponse(
        endpoint.tenantId(),
        endpoint.targetUrl(),
        endpoint.active(),
        endpoint.secret() != null,
        endpoint.previousSecretUntil(),
        endpoint.createdAt(),
        endpoint.updatedAt());
  }
}
