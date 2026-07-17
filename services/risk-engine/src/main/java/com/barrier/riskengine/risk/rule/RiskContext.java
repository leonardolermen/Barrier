package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.util.List;

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
 * @param ip IP de origem da submissão; {@code null} quando o cliente não o informou
 * @param deviceReuseCount quantos subjects distintos usaram o mesmo device recentemente
 *     (0 quando não há {@code deviceId} ou é a primeira vez); calculado antes do motor rodar
 * @param emailReuseCount quantos outros subjects já usaram o mesmo email cadastrado (0 se
 *     nenhum); calculado antes do motor rodar
 * @param documentType tipo de documento (CPF/CNPJ), usado por regras que consultam provider
 *     externo por documento (ex.: score de crédito)
 * @param documentDigits dígitos do documento
 * @param historyEventTypes tipos de evento do histórico interno do subject (nomes de
 *     {@code HistoryEventType}); vazio se não houver histórico
 */
public record RiskContext(
    String assessmentId,
    String tenantId,
    IdentityCheck identity,
    ScreeningResult screening,
    CompanyProfile company,
    SubjectProfile profile,
    String ip,
    long deviceReuseCount,
    long emailReuseCount,
    String documentType,
    String documentDigits,
    List<String> historyEventTypes) {}
