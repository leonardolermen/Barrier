package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.screening.domain.ScreeningResult;

/** Insumo das regras de risco: os resultados de identidade e screening da avaliação. */
public record RiskContext(String assessmentId, IdentityCheck identity, ScreeningResult screening) {}
