package com.barrier.riskengine.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Resultado persistido de uma verificação de identidade para uma avaliação.
 *
 * @param id identificador do registro
 * @param assessmentId avaliação à qual pertence (correlação)
 * @param status desfecho da verificação
 * @param provider nome do bureau consultado
 * @param detail descrição legível do resultado
 * @param checkedAt instante da verificação
 */
public record IdentityCheck(
    UUID id,
    String assessmentId,
    IdentityStatus status,
    String provider,
    String detail,
    Instant checkedAt,
    String providerReference,
    String rawResponse) {

  /** Cria um registro novo com id aleatório e instante atual. */
  public static IdentityCheck create(
      String assessmentId, IdentityStatus status, String provider, String detail) {
    return create(assessmentId, status, provider, detail, null, null);
  }

  /**
   * Registro com o rastro da consulta: id da consulta no provedor e resposta bruta (redigida).
   * Sem eles, "consultamos o bureau" é afirmação nossa sobre nós mesmos — ver migration V031.
   */
  public static IdentityCheck create(
      String assessmentId,
      IdentityStatus status,
      String provider,
      String detail,
      String providerReference,
      String rawResponse) {
    return new IdentityCheck(
        UUID.randomUUID(),
        assessmentId,
        status,
        provider,
        detail,
        Instant.now(),
        providerReference,
        rawResponse);
  }

  public boolean isVerified() {
    return status == IdentityStatus.VERIFIED;
  }

  /** Identidade explicitamente reprovada (não confunde com indisponibilidade do bureau). */
  public boolean isRejected() {
    return status == IdentityStatus.NOT_FOUND
        || status == IdentityStatus.MISMATCH
        || status == IdentityStatus.DECEASED;
  }
}
