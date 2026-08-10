package com.barrier.riskengine.screening.service;

import com.barrier.riskengine.screening.domain.ScreenedParty;
import java.util.List;

/**
 * Comando de screening: o titular e as partes relacionadas a consultar.
 *
 * <p>Usa primitivos e tipos do próprio módulo screening para não depender do módulo assessment
 * (o ArchUnit barra ciclos entre contextos) — o chamador é quem traduz sócios do bureau e
 * representante legal do cadastro para {@link ScreenedParty}.
 *
 * @param relatedParties sócios do QSA e representante legal; vazio para PF ou quando o bureau não
 *     trouxe quadro societário
 */
public record ScreeningCommand(
    String assessmentId,
    String documentType,
    String documentDigits,
    String name,
    List<ScreenedParty> relatedParties) {

  public ScreeningCommand {
    relatedParties = relatedParties == null ? List.of() : List.copyOf(relatedParties);
  }

  /** Screening apenas do titular (PF, ou PJ sem QSA disponível). */
  public ScreeningCommand(
      String assessmentId, String documentType, String documentDigits, String name) {
    this(assessmentId, documentType, documentDigits, name, List.of());
  }
}
