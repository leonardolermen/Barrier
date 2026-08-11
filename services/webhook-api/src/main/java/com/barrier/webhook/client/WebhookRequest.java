package com.barrier.webhook.client;

/**
 * Requisição de entrega de webhook.
 *
 * @param url endpoint do cliente
 * @param body corpo (JSON do resultado da avaliação)
 * @param eventId id do evento (header de idempotência para o cliente)
 * @param signature assinatura HMAC do corpo com o segredo vigente do tenant
 * @param previousSignature assinatura pelo segredo anterior durante a janela de rotação;
 *     {@code null} fora dela. Vai em header próprio em vez de mudar o formato do header principal:
 *     cliente que já verifica {@code X-Barrier-Signature} não precisa saber que existe rotação
 */
public record WebhookRequest(
    String url, String body, String eventId, String signature, String previousSignature) {

  public WebhookRequest(String url, String body, String eventId, String signature) {
    this(url, body, eventId, signature, null);
  }
}
