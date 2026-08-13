package com.barrier.riskengine.subject.profile.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de que um campo do cadastro foi verificado — e de qual valor.
 *
 * <p>O {@code verifiedValue} é o que impede a fraude óbvia: verificação amarrada ao <b>campo</b>
 * deixaria o cliente confirmar um telefone por OTP e depois trocá-lo por outro, mantendo o selo de
 * verificado sobre um valor que ninguém conferiu. Amarrada ao valor, trocar o dado derruba a
 * verificação junto.
 *
 * @param evidence ponteiro para a prova (id do desafio de OTP, {@code QueryId} do bureau) — é o
 *     que sustenta a verificação quando o cliente contesta, meses depois
 */
public record FieldVerification(
    UUID id,
    UUID subjectId,
    String tenantId,
    VerifiableField field,
    VerificationMethod method,
    String verifiedValue,
    String evidence,
    Instant verifiedAt) {

  /** Confere se esta verificação ainda vale para o valor que está no cadastro hoje. */
  public boolean covers(String currentValue) {
    return currentValue != null && normalize(currentValue).equals(normalize(verifiedValue));
  }

  /**
   * Normalização por tipo de campo seria mais precisa, mas aqui basta o denominador comum: caixa e
   * espaço não mudam o que foi verificado, e telefone/e-mail já chegam normalizados de quem grava.
   */
  private static String normalize(String value) {
    return value.trim().toLowerCase();
  }
}
