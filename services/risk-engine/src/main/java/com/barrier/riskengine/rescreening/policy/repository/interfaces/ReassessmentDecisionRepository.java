package com.barrier.riskengine.rescreening.policy.repository.interfaces;

import com.barrier.riskengine.rescreening.policy.domain.ReassessmentDecision;
import java.util.List;
import java.util.UUID;

/** Trilha das decisões de reavaliação (ADR-0019). Escrita sempre; leitura é consulta de auditoria. */
public interface ReassessmentDecisionRepository {

  ReassessmentDecision save(ReassessmentDecision decision);

  /** Histórico do cliente naquele tenant, mais recente primeiro. */
  List<ReassessmentDecision> findBySubject(UUID subjectId, String tenantId, int limit);
}
