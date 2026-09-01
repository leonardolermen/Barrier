package com.barrier.riskengine.risk.registry.repository.interfaces;

import com.barrier.riskengine.policy.RegistryPolicyState;
import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Repositório de domínio do registry de regras de risco. */
public interface RiskRuleRegistryRepository {

  Optional<RiskRuleRegistryEntry> findByRuleCode(String ruleCode);

  /**
   * A entrada de histórico em vigor no instante {@code at} — a mais recente cujo {@code changed_at}
   * não é posterior a ele. Vazio quando não há nenhuma: ou a regra nunca foi alterada, ou toda
   * alteração registrada é posterior, e o chamador distingue os dois com {@link #hasAnyHistory}.
   */
  Optional<RegistryPolicyState> historyAsOf(String ruleCode, Instant at);

  /** Se existe qualquer alteração registrada para a regra. Separa "nunca mudou" de "mudou depois". */
  boolean hasAnyHistory(String ruleCode);

  /** Linha do tempo completa da regra, da alteração mais recente para a mais antiga. */
  List<RegistryPolicyState> history(String ruleCode);

  List<RiskRuleRegistryEntry> findAll();

  RiskRuleRegistryEntry upsert(
      String ruleCode,
      String description,
      String criticality,
      boolean enabled,
      java.time.Instant validFrom,
      java.time.Instant validUntil,
      String updatedBy);
}
