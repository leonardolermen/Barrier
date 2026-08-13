package com.barrier.riskengine.risk.registry.controller.dto;

import java.time.Instant;

/**
 * Atualização do estado operacional de uma família de regra. Todos os campos são obrigatórios.
 *
 * @param updatedBy quem está mudando. Obrigatório pelo mesmo motivo que em
 *     {@code tenant_risk_config}: desligar uma regra de risco é a operação mais sensível do
 *     sistema, e era a única sem autoria — o {@code X-Admin-Key} prova que quem chamou tinha a
 *     chave, não quem decidiu
 */
public record UpsertRiskRuleRegistryRequest(
    String description,
    String criticality,
    boolean enabled,
    Instant validFrom,
    Instant validUntil,
    String updatedBy) {}
