package com.barrier.webhook.controller;

/**
 * Corpo do registro de endpoint.
 *
 * @param targetUrl URL de callback do tenant; validada no domínio ({@code WebhookEndpoint}), que é
 *     onde a regra de TLS vale independentemente de por onde o registro entrou
 */
public record RegisterEndpointRequest(String targetUrl) {}
