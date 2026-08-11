package com.barrier.riskengine.identity.client;

import com.barrier.riskengine.identity.domain.CompanyProfile;

/**
 * Desfecho retornado por um bureau (indisponibilidade é sinalizada por exceção).
 *
 * @param outcome resultado da verificação
 * @param detail descrição legível
 * @param company perfil da PJ quando o bureau o fornece (Receita); {@code null} para CPF/stubs
 */
public record BureauResult(Outcome outcome, String detail, CompanyProfile company) {

  public enum Outcome {
    MATCH,
    NOT_FOUND,
    MISMATCH,
    /**
     * Documento encontrado, titular falecido.
     *
     * <p>Tem desfecho próprio em vez de virar {@code NOT_FOUND} porque a trilha precisa dizer a
     * verdade: o CPF <i>foi</i> encontrado — quem morreu foi o titular. Registrar "documento não
     * encontrado no bureau" para um falecido dá a decisão certa com a explicação errada, e uso de
     * CPF de falecido é indício de fraude, não ambiguidade de cadastro.
     */
    DECEASED
  }

  /** Conveniência para bureaus que não trazem perfil de PJ (CPF, stubs). */
  public BureauResult(Outcome outcome, String detail) {
    this(outcome, detail, null);
  }

  public static BureauResult match(String detail) {
    return new BureauResult(Outcome.MATCH, detail, null);
  }
}
