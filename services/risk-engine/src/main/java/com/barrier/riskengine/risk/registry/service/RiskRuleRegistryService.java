package com.barrier.riskengine.risk.registry.service;

import com.barrier.riskengine.policy.RegistryPolicyState;
import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Consulta e gestão do registry de regras: permite habilitar/desabilitar uma família de regra
 * inteira e limitar sua vigência sem deploy. Regra sem linha no registry é considerada ativa
 * por padrão (fail-open — o registry é um kill switch operacional, não uma allowlist).
 */
public interface RiskRuleRegistryService {

  boolean isActive(String ruleCode);

  List<RiskRuleRegistryEntry> findAll();

  /**
   * O estado do registry para esta regra <b>no instante {@code at}</b>, com autoria.
   *
   * <p>Vazio significa {@code UNKNOWN_BEFORE_HISTORY}: existe histórico, mas todo posterior ao
   * instante — o estado anterior à primeira alteração registrada não foi gravado por ninguém. Não
   * confundir com "regra nunca alterada", que devolve o estado atual marcado como
   * {@code UNCHANGED_SINCE_SEED}.
   */
  Optional<RegistryPolicyState> stateAsOf(String ruleCode, Instant at);

  /** Linha do tempo de alterações da regra, da mais recente para a mais antiga. */
  List<RegistryPolicyState> history(String ruleCode);

  RiskRuleRegistryEntry upsert(
      String ruleCode,
      String description,
      String criticality,
      boolean enabled,
      java.time.Instant validFrom,
      java.time.Instant validUntil,
      String updatedBy);
}
