package com.barrier.riskengine.tenant.config.controller.dto;

/** Override de um único parâmetro de regra de risco para o tenant do path. */
public record UpsertRiskConfigRequest(
    String ruleCode, String paramKey, String paramValue, String updatedBy) {}
