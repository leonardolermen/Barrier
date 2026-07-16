package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.screening.domain.ScreeningResult;

/**
 * Insumo das regras de risco: os resultados de identidade e screening da avaliação, mais o
 * perfil objetivo da PJ (abertura/CNAE/QSA) quando disponível.
 *
 * @param assessmentId avaliação avaliada
 * @param tenantId tenant dono da avaliação; usado pelas regras para ler overrides de config
 * @param identity resultado da verificação de identidade
 * @param screening resultado do screening em listas restritivas
 * @param company perfil da PJ; {@code null} para CPF ou quando o bureau não o forneceu
 */
public record RiskContext(
    String assessmentId,
    String tenantId,
    IdentityCheck identity,
    ScreeningResult screening,
    CompanyProfile company) {}
