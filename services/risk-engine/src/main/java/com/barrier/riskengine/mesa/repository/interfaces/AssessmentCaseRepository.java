package com.barrier.riskengine.mesa.repository.interfaces;

import com.barrier.riskengine.mesa.domain.AssessmentCase;
import com.barrier.riskengine.mesa.domain.CaseQueue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Casos da mesa. Toda assinatura carrega {@code tenantId} — mesma defesa por tipo do
 * {@code SubjectProfileRepository}: caso de um parceiro não pode ser lido nem movido por outro.
 */
public interface AssessmentCaseRepository {

  AssessmentCase save(AssessmentCase caso);

  Optional<AssessmentCase> find(UUID assessmentId, String tenantId);

  /** Fila aberta, mais antigo primeiro — é a ordem de trabalho do analista. */
  List<AssessmentCase> findOpenByQueue(String tenantId, CaseQueue queue, int limit);
}
