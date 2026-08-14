package com.barrier.riskengine.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Resultado persistido de uma verificação de identidade para uma avaliação.
 *
 * @param id identificador do registro
 * @param assessmentId avaliação à qual pertence (correlação)
 * @param tenantId tenant dono da avaliação; escopo do reuso — nunca compartilhado entre tenants
 * @param documentType tipo do documento verificado (hoje só CPF entra no reuso)
 * @param documentDigits dígitos do documento, sem máscara
 * @param name nome comparado contra o bureau; entra na chave do reuso (ver {@link #reusing})
 * @param status desfecho da verificação
 * @param provider nome do bureau consultado
 * @param detail descrição legível do resultado
 * @param checkedAt instante da verificação
 * @param reusedFromId quando preenchido, este check copiou o desfecho do check apontado em vez
 *     de ir ao bureau
 */
public record IdentityCheck(
    UUID id,
    String assessmentId,
    String tenantId,
    String documentType,
    String documentDigits,
    String name,
    IdentityStatus status,
    String provider,
    String detail,
    Instant checkedAt,
    String providerReference,
    String rawResponse,
    UUID reusedFromId) {

  /** Cria um registro novo com id aleatório e instante atual. */
  public static IdentityCheck create(
      String assessmentId, IdentityStatus status, String provider, String detail) {
    return create(assessmentId, status, provider, detail, null, null);
  }

  /**
   * Registro com o rastro da consulta: id da consulta no provedor e resposta bruta (redigida).
   * Sem eles, "consultamos o bureau" é afirmação nossa sobre nós mesmos — ver migration V031.
   *
   * <p>Fábrica de conveniência para chamadores que ainda não têm tenant/documento/nome à mão;
   * delega para a fábrica completa com esses campos nulos, o que torna o check inelegível a
   * reuso (a consulta de reuso exige os quatro preenchidos).
   */
  public static IdentityCheck create(
      String assessmentId,
      IdentityStatus status,
      String provider,
      String detail,
      String providerReference,
      String rawResponse) {
    return create(
        assessmentId, null, null, null, null, status, provider, detail, providerReference,
        rawResponse);
  }

  /**
   * Fábrica completa: inclui tenant, documento e nome, o que torna o check pesquisável para
   * reuso por {@code IdentityCheckRepository.findReusable}. Ver migration V040.
   */
  public static IdentityCheck create(
      String assessmentId,
      String tenantId,
      String documentType,
      String documentDigits,
      String name,
      IdentityStatus status,
      String provider,
      String detail,
      String providerReference,
      String rawResponse) {
    return new IdentityCheck(
        UUID.randomUUID(),
        assessmentId,
        tenantId,
        documentType,
        documentDigits,
        name,
        status,
        provider,
        detail,
        Instant.now(),
        providerReference,
        rawResponse,
        null);
  }

  /**
   * Verificação que reaproveitou uma consulta anterior em vez de ir ao bureau.
   *
   * <p>{@code checkedAt} é <b>agora</b>, não o instante da consulta original: este é o momento em
   * que esta avaliação decidiu. Quando a consulta de fato aconteceu se lê seguindo {@code
   * reusedFromId} — que é por isso que a coluna existe.
   *
   * <p>A resposta bruta não é copiada: PII duplicada não é evidência a mais, e o original
   * continua acessível pelo ponteiro.
   */
  public static IdentityCheck reusing(String assessmentId, IdentityCheck original) {
    return new IdentityCheck(
        UUID.randomUUID(),
        assessmentId,
        original.tenantId(),
        original.documentType(),
        original.documentDigits(),
        original.name(),
        original.status(),
        original.provider(),
        original.detail(),
        Instant.now(),
        original.providerReference(),
        null,
        original.id());
  }

  /** Este check foi à rede, ou copiou outro? */
  public boolean isReused() {
    return reusedFromId != null;
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
