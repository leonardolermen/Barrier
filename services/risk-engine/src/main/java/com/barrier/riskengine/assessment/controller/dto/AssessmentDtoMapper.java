package com.barrier.riskengine.assessment.controller.dto;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.identity.domain.IdentityProvenance;

/**
 * Conversão do domínio para o DTO de resposta. {@code factors} traz os fatores explicáveis da
 * decisão de risco (exigência regulatória de explicabilidade).
 */
public final class AssessmentDtoMapper {

  private AssessmentDtoMapper() {}

  /**
   * Resposta sem procedência de identidade — usada onde o check ainda não é conhecido (POST
   * 202, decisão manual). {@code identityReused}/{@code identityCheckedAt} entram nulos.
   */
  public static AssessmentResponse toResponse(Assessment a) {
    return toResponse(a, null);
  }

  /**
   * {@code provenance} nulo (sem check ainda, ou avaliação sem cadeia de bureau) deixa
   * {@code identityReused}/{@code identityCheckedAt} nulos — diferente de {@code false}, que
   * afirmaria "consulta fresca" sobre algo que não existe.
   */
  public static AssessmentResponse toResponse(Assessment a, IdentityProvenance provenance) {
    return new AssessmentResponse(
        a.id().asString(),
        a.status().name(),
        a.riskLevel() == null ? null : a.riskLevel().name(),
        a.decision(),
        a.factors(),
        a.createdAt(),
        a.completedAt(),
        a.reviewedBy(),
        a.reviewedByKey(),
        a.reviewReason(),
        a.reviewedAt(),
        provenance == null ? null : provenance.reused(),
        provenance == null ? null : provenance.checkedAt());
  }
}
