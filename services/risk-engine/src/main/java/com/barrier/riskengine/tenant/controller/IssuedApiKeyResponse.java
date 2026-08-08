package com.barrier.riskengine.tenant.controller;

/**
 * Credencial recém-emitida.
 *
 * @param keyId parte pública, para identificar e revogar a chave depois
 * @param apiKey valor completo — <b>só aparece aqui, uma vez</b>
 * @param warning lembrete de que não há recuperação
 */
public record IssuedApiKeyResponse(String keyId, String apiKey, String warning) {}
