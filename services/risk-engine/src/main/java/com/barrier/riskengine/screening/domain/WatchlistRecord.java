package com.barrier.riskengine.screening.domain;

/**
 * Um registro de uma lista restritiva, já ingerido na base local.
 *
 * @param source origem da lista (ex.: CEIS, OFAC)
 * @param type categoria (sanção, PEP, mídia adversa)
 * @param document CPF/CNPJ associado (listas por documento); {@code null} para listas por nome
 * @param name nome na lista
 * @param detail descrição legível
 */
public record WatchlistRecord(
    String source, MatchType type, String document, String name, String detail) {}
