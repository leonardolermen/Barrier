package com.barrier.webhook.client;

/**
 * Requisição de entrega de webhook.
 *
 * @param url endpoint do cliente
 * @param body corpo (JSON do resultado da avaliação)
 * @param eventId id do evento (header de idempotência para o cliente)
 * @param signature assinatura HMAC do corpo
 */
public record WebhookRequest(String url, String body, String eventId, String signature) {}
