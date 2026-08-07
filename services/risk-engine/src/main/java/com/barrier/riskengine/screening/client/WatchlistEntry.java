package com.barrier.riskengine.screening.client;

import com.barrier.riskengine.screening.domain.MatchBasis;
import com.barrier.riskengine.screening.domain.MatchType;

/**
 * Registro bruto retornado por uma lista restritiva.
 *
 * @param type categoria do apontamento
 * @param basis por qual atributo o provider casou (documento ou nome) — cada implementação de
 *     {@link WatchlistProvider} sabe como encontrou e é quem preenche isto
 * @param source origem da lista
 * @param matchedName nome que casou na lista
 * @param detail descrição legível
 */
public record WatchlistEntry(
    MatchType type, MatchBasis basis, String source, String matchedName, String detail) {}
