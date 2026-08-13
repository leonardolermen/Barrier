package com.barrier.riskengine.assurance.repository.interfaces;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resultados de documentoscopia e biometria — nunca as imagens (ADR-0016). */
public interface AssuranceCheckRepository {

  void save(AssuranceCheck check);

  /**
   * Verificação mais recente daquele tipo. É a que vale: refazer a biometria depois de uma falha é
   * o fluxo normal (foto ruim), e a decisão tem de olhar a última tentativa, não a pior.
   */
  Optional<AssuranceCheck> findLatest(UUID subjectId, String tenantId, AssuranceKind kind);

  /** Histórico completo: as tentativas anteriores continuam na trilha para auditoria. */
  List<AssuranceCheck> findAll(UUID subjectId, String tenantId);
}
