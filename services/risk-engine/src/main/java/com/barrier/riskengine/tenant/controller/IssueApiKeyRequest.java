package com.barrier.riskengine.tenant.controller;

/**
 * Pedido de emissão de credencial.
 *
 * @param name rótulo operacional da chave (ex.: "integração produção", "rotação 2026-08"); entra
 *     na trilha das decisões manuais tomadas com ela
 */
public record IssueApiKeyRequest(String name) {

  public IssueApiKeyRequest {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name da chave é obrigatório");
    }
  }
}
