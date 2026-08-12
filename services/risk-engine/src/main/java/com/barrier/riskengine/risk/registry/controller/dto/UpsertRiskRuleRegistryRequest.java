package com.barrier.riskengine.risk.registry.controller.dto;

import java.time.Instant;

/** Atualização do estado operacional de uma família de regra. Todos os campos são obrigatórios. */
public record UpsertRiskRuleRegistryRequest(
    String description,
    String criticality,
    boolean enabled,
    Instant validFrom,
    Instant validUntil) {}
