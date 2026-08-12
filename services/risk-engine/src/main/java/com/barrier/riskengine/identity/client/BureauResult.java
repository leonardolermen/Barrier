package com.barrier.riskengine.identity.client;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.PersonProfile;

/**
 * Desfecho retornado por um bureau (indisponibilidade é sinalizada por exceção).
 *
 * @param outcome resultado da verificação
 * @param detail descrição legível
 * @param company perfil da PJ quando o bureau o fornece (Receita); {@code null} para CPF/stubs
 * @param person perfil da PF quando o bureau o fornece; {@code null} para CNPJ. Sem ele, os dados
 *     cadastrais objetivos de pessoa física nunca chegavam ao cadastro, e toda avaliação de PF era
 *     rebaixada para revisão por cadastro incompleto mesmo com o bureau tendo respondido
 */
public record BureauResult(
    Outcome outcome,
    String detail,
    CompanyProfile company,
    PersonProfile person,
    BureauTrace trace) {

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

  /** Conveniência para bureaus que não trazem perfil (stubs, desfechos sem dados). */
  public BureauResult(Outcome outcome, String detail) {
    this(outcome, detail, null, null, null);
  }

  /** Anexa o rastro da consulta a um desfecho já decidido — vale para qualquer outcome. */
  public BureauResult withTrace(BureauTrace trace) {
    return new BureauResult(outcome, detail, company, person, trace);
  }

  /** Conveniência para bureaus de PJ. */
  public BureauResult(Outcome outcome, String detail, CompanyProfile company) {
    this(outcome, detail, company, null, null);
  }

  /** Conveniência para bureaus de PF. */
  public static BureauResult of(Outcome outcome, String detail, PersonProfile person) {
    return new BureauResult(outcome, detail, null, person, null);
  }

  public static BureauResult match(String detail) {
    return new BureauResult(Outcome.MATCH, detail, null, null, null);
  }
}
