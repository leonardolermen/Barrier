package com.barrier.riskengine.screening.client;

/** Consulta a listas restritivas. */
public record WatchlistQuery(String documentType, String documentDigits, String name) {}
