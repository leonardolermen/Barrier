package com.barrier.riskengine.screening.domain;

import com.barrier.riskengine.screening.domain.enums.MatchType;

/**
 * Um registro de uma lista restritiva, já ingerido na base local.
 *
 * @param source origem da lista (ex.: CEIS, OFAC, PEP)
 * @param type categoria (sanção, PEP, mídia adversa)
 * @param document CPF/CNPJ completo (listas por documento); {@code null} para listas por nome ou
 *     quando a fonte só publica o documento mascarado
 * @param documentPartial dígitos centrais do CPF quando a fonte mascara o documento (ex.: PEP da
 *     CGU); usado como discriminador do match por nome, nunca para match exato
 * @param name nome na lista
 * @param detail descrição legível
 */
public record WatchlistRecord(
    String source,
    MatchType type,
    String document,
    String documentPartial,
    String name,
    String detail) {

  /** Registro de lista que publica o documento completo (ou nenhum). */
  public WatchlistRecord(
      String source, MatchType type, String document, String name, String detail) {
    this(source, type, document, null, name, detail);
  }
}
