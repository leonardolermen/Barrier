package com.barrier.riskengine.screening.client;

import com.barrier.riskengine.screening.domain.MatchType;

/** Registro bruto retornado por uma lista restritiva. */
public record WatchlistEntry(MatchType type, String source, String matchedName, String detail) {}
