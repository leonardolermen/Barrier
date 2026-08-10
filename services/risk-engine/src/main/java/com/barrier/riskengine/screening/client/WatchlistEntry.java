package com.barrier.riskengine.screening.client;

import com.barrier.riskengine.screening.domain.MatchBasis;
import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreenedParty;

/**
 * Registro bruto retornado por uma lista restritiva.
 *
 * @param type categoria do apontamento
 * @param basis por qual atributo o provider casou (documento ou nome) — cada implementação de
 *     {@link WatchlistProvider} sabe como encontrou e é quem preenche isto
 * @param party a quem o apontamento se refere: o titular, um sócio ou o representante legal
 * @param source origem da lista
 * @param matchedName nome que casou na lista
 * @param detail descrição legível
 */
public record WatchlistEntry(
    MatchType type,
    MatchBasis basis,
    ScreenedParty party,
    String source,
    String matchedName,
    String detail) {}
