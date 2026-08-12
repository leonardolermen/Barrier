package com.barrier.riskengine.assurance.domain;

import java.time.Instant;

/**
 * Consentimento do titular para a verificação de documentoscopia ou biometria.
 *
 * <p>Vive por {@code AssuranceCheck}, não por subject: a LGPD exige prova de consentimento para
 * AQUELA finalidade no momento DAQUELE tratamento, não um flag global de "o titular já
 * consentiu alguma vez". Um consentimento dado para reabrir conta não cobre, por si, uma
 * verificação pedida meses depois para outro fim — cada tratamento tem de carregar a sua prova.
 *
 * @param reference identificador do registro de consentimento (ex.: id da tela de aceite)
 * @param purpose finalidade declarada ao titular no momento da coleta
 * @param grantedAt quando o titular consentiu; nunca no futuro
 */
public record AssuranceConsent(String reference, String purpose, Instant grantedAt) {

  /** Consentimento sem finalidade ou datado no futuro não prova nada — recusa cedo. */
  public void validate() {
    if (purpose == null || purpose.isBlank()) {
      throw new IllegalArgumentException("consentimento sem finalidade");
    }
    if (grantedAt == null || grantedAt.isAfter(Instant.now())) {
      throw new IllegalArgumentException("consentimento com data de concessão no futuro");
    }
  }
}
