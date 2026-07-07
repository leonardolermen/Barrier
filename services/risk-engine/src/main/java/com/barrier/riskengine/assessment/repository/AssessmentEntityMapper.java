package com.barrier.riskengine.assessment.repository;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import java.util.List;

/** Conversão entre o agregado de domínio e a entidade JPA. */
final class AssessmentEntityMapper {

  private AssessmentEntityMapper() {}

  static AssessmentEntity toEntity(Assessment a) {
    AssessmentEntity e = new AssessmentEntity();
    e.setId(a.id().value());
    e.setTenantId(a.tenantId());
    e.setDocumentType(a.documentType());
    e.setDocumentValue(a.documentDigits());
    e.setName(a.name());
    e.setStatus(a.status());
    e.setRiskLevel(a.riskLevel());
    e.setDecision(a.decision());
    e.setFactors(a.factors().isEmpty() ? null : String.join("\n", a.factors()));
    e.setCreatedAt(a.createdAt());
    e.setCompletedAt(a.completedAt());
    return e;
  }

  static Assessment toDomain(AssessmentEntity e) {
    return Assessment.rehydrate(
        new AssessmentId(e.getId()),
        e.getTenantId(),
        e.getDocumentType(),
        e.getDocumentValue(),
        e.getName(),
        e.getStatus(),
        e.getRiskLevel(),
        e.getDecision(),
        parseFactors(e.getFactors()),
        e.getCreatedAt(),
        e.getCompletedAt());
  }

  private static List<String> parseFactors(String factors) {
    if (factors == null || factors.isBlank()) {
      return List.of();
    }
    return List.of(factors.split("\n"));
  }
}
