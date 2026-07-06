package com.barrier.riskengine.screening.domain;

/**
 * Um apontamento encontrado no screening.
 *
 * @param type categoria (PEP, sanção, mídia adversa)
 * @param source origem da lista (ex.: OFAC, ONU, CGU)
 * @param matchedName nome que casou na lista
 * @param detail descrição legível do apontamento
 */
public record ScreeningHit(MatchType type, String source, String matchedName, String detail) {}
