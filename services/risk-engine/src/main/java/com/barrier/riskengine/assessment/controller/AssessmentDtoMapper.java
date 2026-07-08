package com.barrier.riskengine.assessment.controller;

import com.barrier.riskengine.assessment.domain.Assessment;

/**
 * Conversão do domínio para o DTO de resposta. {@code factors} traz os fatores explicáveis da
 * decisão de risco (exigência regulatória de explicabilidade).
 */
final class AssessmentDtoMapper {

  private AssessmentDtoMapper() {}

  static AssessmentResponse toResponse(Assessment a) {
    return new AssessmentResponse(
        a.id().asString(),
        a.status().name(),
        a.riskLevel() == null ? null : a.riskLevel().name(),
        a.decision(),
        a.factors(),
        a.createdAt(),
        a.completedAt(),
        a.reviewedBy(),
        a.reviewReason(),
        a.reviewedAt());
  }
}
