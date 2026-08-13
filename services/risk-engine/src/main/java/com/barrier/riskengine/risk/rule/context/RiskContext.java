package com.barrier.riskengine.risk.rule.context;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;

/**
 * Insumo das regras de risco: os resultados de identidade e screening da avaliação, o perfil
 * objetivo da PJ (abertura/CNAE/QSA) quando disponível, e o cadastro do subject (endereço/
 * telefone) para regras de consistência.
 *
 * @param assessmentId avaliação avaliada
 * @param tenantId tenant dono da avaliação; usado pelas regras para ler overrides de config
 * @param identity resultado da verificação de identidade
 * @param screening resultado do screening em listas restritivas
 * @param company perfil da PJ; {@code null} para CPF ou quando o bureau não o forneceu
 * @param profile cadastro do subject (endereço/telefone/etc.); pode estar em branco
 * @param assurance resultados de documentoscopia e biometria; {@code null} quando o parceiro não
 *     usa essa etapa. Ausência é diferente de falha, e as regras tratam as duas de forma diferente
 */
public record RiskContext(
    String assessmentId,
    String tenantId,
    IdentityCheck identity,
    ScreeningResult screening,
    CompanyProfile company,
    SubjectProfile profile,
    AssuranceSummary assurance) {}
