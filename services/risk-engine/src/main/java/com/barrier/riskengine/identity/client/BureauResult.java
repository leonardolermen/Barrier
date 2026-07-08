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
    MISMATCH
  }

  /** Conveniência para bureaus que não trazem perfil de PJ (CPF, stubs). */
  public BureauResult(Outcome outcome, String detail) {
    this(outcome, detail, null);
  }

  public static BureauResult match(String detail) {
    return new BureauResult(Outcome.MATCH, detail, null);
  }
}
