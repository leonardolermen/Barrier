package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.identity.domain.IdentityProvenance;
import java.time.Instant;

/**
 * Payload do evento {@code barrier.assessment.completed} (contrato v1 — acréscimo retrocompatível
 * de campo — ver docs/architecture/event-catalog.md).
 *
 * <p>{@code identityReused}/{@code identityCheckedAt} existem porque o parceiro recebe o desfecho
 * por este evento, não pelo {@code GET} — se a decisão se apoia numa verificação de ontem, é
 * informação que ele precisa para a própria trilha dele. Ambos nulos quando a avaliação não tem
 * check de identidade conhecido (ex.: falha antes de chegar ao bureau).
 */
public record AssessmentCompletedPayload(
    String assessmentId,
    String tenantId,
    String status,
    String riskLevel,
    String decision,
    Instant completedAt,
    Boolean identityReused,
    Instant identityCheckedAt) {

  static AssessmentCompletedPayload from(Assessment a, IdentityProvenance provenance) {
    return new AssessmentCompletedPayload(
        a.id().asString(),
        a.tenantId(),
        a.status().name(),
        a.riskLevel() == null ? null : a.riskLevel().name(),
        a.decision(),
        a.completedAt(),
        provenance == null ? null : provenance.reused(),
        provenance == null ? null : provenance.checkedAt());
  }
}
