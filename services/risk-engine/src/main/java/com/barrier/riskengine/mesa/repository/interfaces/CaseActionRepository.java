package com.barrier.riskengine.mesa.repository.interfaces;

import com.barrier.riskengine.mesa.domain.CaseAction;
import java.util.List;
import java.util.UUID;

/** Trilha de ações manuais. Só insere: ação registrada não se edita nem se apaga. */
public interface CaseActionRepository {

  CaseAction append(CaseAction action);

  /** Linha do tempo do caso, mais antiga primeiro. É a evidência de que o SLA é derivado. */
  List<CaseAction> findByCase(UUID assessmentId, String tenantId);
}
