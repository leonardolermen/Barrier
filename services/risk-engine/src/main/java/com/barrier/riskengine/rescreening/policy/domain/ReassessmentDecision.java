package com.barrier.riskengine.rescreening.policy.domain;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import java.time.Instant;
import java.util.UUID;

/**
 * O que a política decidiu sobre reavaliar um cliente — e, quando a resposta é não, por quê.
 *
 * <p>É gravada nos dois casos. Sem a linha do "não", um rescreening que não gerou avaliação fica
 * indistinguível de um que nunca rodou (ADR-0019).
 *
 * @param riskLevel nível corrente no momento da decisão, de que saiu o intervalo aplicado; nulo
 *     quando o cliente ainda não tem projeção (tratado como pior caso)
 */
public record ReassessmentDecision(
    UUID id,
    UUID subjectId,
    String tenantId,
    ReassessmentTrigger trigger,
    String triggerDetail,
    boolean reassess,
    String reason,
    RiskLevel riskLevel,
    Instant decidedAt) {

  /** Motivos de recusa — vocabulário fechado, para a trilha ser consultável. */
  public static final String INTERVALO_MINIMO = "intervalo_minimo";

  public static final String SEM_ALTERACAO_MATERIAL = "sem_alteracao_material";

  public static ReassessmentDecision sim(
      UUID subjectId,
      String tenantId,
      ReassessmentTrigger trigger,
      String triggerDetail,
      RiskLevel riskLevel) {
    return new ReassessmentDecision(
        UUID.randomUUID(), subjectId, tenantId, trigger, triggerDetail, true, null, riskLevel,
        Instant.now());
  }

  public static ReassessmentDecision nao(
      UUID subjectId,
      String tenantId,
      ReassessmentTrigger trigger,
      String triggerDetail,
      String reason,
      RiskLevel riskLevel) {
    return new ReassessmentDecision(
        UUID.randomUUID(), subjectId, tenantId, trigger, triggerDetail, false, reason, riskLevel,
        Instant.now());
  }
}
