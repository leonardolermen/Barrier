package com.barrier.riskengine.riskstate.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.riskstate.domain.RiskLevelTransition;
import java.time.Instant;

/**
 * Conteúdo de {@code barrier.subject.risk_level_changed} — o fato "o risco deste cliente mudou de
 * faixa", que transforma o produto de consulta em assinatura: o parceiro passa a saber que o
 * cliente dele piorou sem precisar perguntar.
 *
 * <p><b>Escala:</b> a do Barrier é <i>maior = pior</i> (LOW → CRITICAL). O campo {@code worsened}
 * vai explícito para o parceiro não ter que reimplementar a ordenação do enum do lado dele — e
 * errar, já que o ecossistema Origem usa a escala invertida e a confusão é previsível.
 *
 * <p><b>Por que {@code origin} viaja junto.</b> Mudança de nível causada por reavaliação sem fato
 * novo não deveria acordar ninguém de madrugada, mas a política de notificação é do parceiro, não
 * nossa — filtrar aqui seria decidir por ele. Mandamos ONBOARDING/RESCREENING/ASSURANCE e ele
 * decide o que faz com cada um.
 *
 * <p>O documento vai <b>mascarado</b>, como em todo payload que sai daqui.
 */
public record RiskLevelChangedPayload(
    String tenantId,
    String subjectId,
    String documentType,
    String document,
    String previousLevel,
    String currentLevel,
    boolean worsened,
    String decision,
    String assessmentId,
    String origin,
    String engineVersion,
    Instant changedAt) {

  public static RiskLevelChangedPayload from(
      Assessment assessment, RiskLevelTransition transition, String engineVersion) {
    return new RiskLevelChangedPayload(
        assessment.tenantId(),
        assessment.subjectId(),
        assessment.documentType().name(),
        assessment.maskedDocument(),
        transition.from().name(),
        transition.to().name(),
        transition.worsened(),
        assessment.status().name(),
        assessment.id().asString(),
        assessment.origin().name(),
        engineVersion,
        Instant.now());
  }
}
