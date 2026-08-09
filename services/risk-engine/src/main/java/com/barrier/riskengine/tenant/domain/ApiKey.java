package com.barrier.riskengine.tenant.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Credencial de um tenant. O segredo em claro <b>não</b> existe aqui: só o hash. Ele é devolvido
 * uma única vez, no momento da emissão, e depois é irrecuperável — se o cliente perder, emite-se
 * outra.
 *
 * @param id identificador do registro
 * @param tenantId tenant que a chave autentica
 * @param keyId parte pública da chave, usada para localizar a linha sem varrer hashes
 * @param secretHash SHA-256 do segredo, em hexadecimal
 * @param name rótulo operacional (ex.: "integração produção", "rotação 2026-08") — entra na
 *     trilha de decisão manual, para saber qual credencial agiu
 * @param createdAt emissão
 * @param revokedAt revogação; {@code null} enquanto vale
 */
public record ApiKey(
    UUID id,
    String tenantId,
    String keyId,
    String secretHash,
    String name,
    Instant createdAt,
    Instant revokedAt) {

  public boolean isActive() {
    return revokedAt == null;
  }
}
