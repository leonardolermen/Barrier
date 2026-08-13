package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import java.time.Instant;

/** Payload do evento {@code barrier.assessment.completed} (contrato v1). */
public record AssessmentCompletedPayload(
    String assessmentId,
    String tenantId,
    String status,
    String riskLevel,
    String decision,
    Instant completedAt) {

  static AssessmentCompletedPayload from(Assessment a) {
    return new AssessmentCompletedPayload(
        a.id().asString(),
        a.tenantId(),
        a.status().name(),
        a.riskLevel() == null ? null : a.riskLevel().name(),
        a.decision(),
        a.completedAt());
  }
}
