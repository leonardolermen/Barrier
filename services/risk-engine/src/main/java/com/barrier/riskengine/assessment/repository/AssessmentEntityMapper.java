package com.barrier.riskengine.assessment.repository;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import java.util.List;
import java.util.UUID;

/** Conversão entre o agregado de domínio e a entidade JPA. */
final class AssessmentEntityMapper {

  private AssessmentEntityMapper() {}

  /**
   * Copia o estado do agregado para uma entidade <b>existente</b>, em vez de construir uma nova.
   *
   * <p>É o que preserva o {@code @Version}: uma entidade nova sempre teria versão zerada, o que
   * (a) faria o Spring Data tratá-la como novo registro e tentar {@code persist} sobre uma linha
   * que já existe, e (b) anularia o controle de concorrência otimista, que é justamente o ponto
   * de ter a coluna. O agregado de domínio não conhece a versão — ela é detalhe de persistência e
   * fica onde deve ficar.
   */
  static void copyInto(Assessment a, AssessmentEntity e) {
    e.setId(a.id().value());
    e.setTenantId(a.tenantId());
    e.setSubjectId(a.subjectId() == null ? null : UUID.fromString(a.subjectId()));
    e.setDocumentType(a.documentType());
    e.setDocumentValue(a.documentDigits());
    e.setName(a.name());
    e.setStatus(a.status());
    e.setRiskLevel(a.riskLevel());
    e.setDecision(a.decision());
    e.setFactors(a.factors().isEmpty() ? null : String.join("\n", a.factors()));
    e.setCreatedAt(a.createdAt());
    e.setCompletedAt(a.completedAt());
    e.setReviewedBy(a.reviewedBy());
    e.setReviewedByKey(a.reviewedByKey());
    e.setReviewReason(a.reviewReason());
    e.setReviewedAt(a.reviewedAt());
    e.setAttempts(a.attempts());
    e.setLastError(a.lastError());
    e.setNextAttemptAt(a.nextAttemptAt());
    e.setCorrelationId(a.correlationId());
  }

  static Assessment toDomain(AssessmentEntity e) {
    return Assessment.rehydrate(
        new AssessmentId(e.getId()),
        e.getTenantId(),
        e.getSubjectId() == null ? null : e.getSubjectId().toString(),
        e.getDocumentType(),
        e.getDocumentValue(),
        e.getName(),
        e.getStatus(),
        e.getRiskLevel(),
        e.getDecision(),
        parseFactors(e.getFactors()),
        e.getCreatedAt(),
        e.getCompletedAt(),
        e.getReviewedBy(),
        e.getReviewedByKey(),
        e.getReviewReason(),
        e.getReviewedAt(),
        e.getAttempts(),
        e.getLastError(),
        e.getNextAttemptAt(),
        e.getVersion(),
        e.getCorrelationId());
  }

  private static List<String> parseFactors(String factors) {
    if (factors == null || factors.isBlank()) {
      return List.of();
    }
    return List.of(factors.split("\n"));
  }
}
