package com.barrier.riskengine.riskpolicy.repository;

import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField;

/**
 * Uma linha de {@code risk_policies.rules_json} referencia um id de {@link PolicyField} que o
 * {@link FieldCatalog} atual não resolve.
 *
 * <p>Isto é erro alto de propósito, nunca um retorno silencioso. Campo do catálogo pode ser
 * marcado obsoleto mas nunca é removido (mesma razão de migration Flyway ser imutável) — então um
 * id que não resolve mais é corrupção de dado ou bug de deploy (schema do catálogo divergente do
 * banco), não um caso de negócio legítimo a engolir como "regra não dispara".
 */
public class UnknownPolicyFieldException extends RuntimeException {

  public UnknownPolicyFieldException(String message) {
    super(message);
  }
}
