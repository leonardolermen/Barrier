package com.barrier.riskengine.identity.client;

/** Consulta a um bureau de identidade. */
public record BureauQuery(String documentType, String documentDigits, String name) {}
