package com.barrier.riskengine.assessment.controller;

import com.barrier.riskengine.assessment.domain.Assessment;
import java.util.List;

/**
 * Conversão do domínio para o DTO de resposta.
 *
 * <p>Mapeamento manual por ora; pode migrar para MapStruct após o primeiro build verde.
 * {@code factors} fica vazio até a Fase 4 (risk scoring).
 */
final class AssessmentDtoMapper {

  private AssessmentDtoMapper() {}

  static AssessmentResponse toResponse(Assessment a) {
    return new AssessmentResponse(
        a.id().asString(),
        a.status().name(),
        a.riskLevel() == null ? null : a.riskLevel().name(),
        a.decision(),
        List.of(),
        a.createdAt(),
        a.completedAt());
  }
}
