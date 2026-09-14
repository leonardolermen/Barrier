package com.barrier.riskengine.riskpolicy.repository.interfaces;

import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Versões de política. Não há {@code update} de conteúdo: versão ativada é imutável, e editar
 * gera versão nova. A ausência do método é a defesa, mesmo raciocínio de {@code
 * BehaviorEventRepository}. As únicas mutações são transição de estado.
 */
public interface RiskPolicyRepository {

  RiskPolicy create(RiskPolicy policy);

  Optional<RiskPolicy> findActive(String tenantId, PolicyDomain domain);

  Optional<RiskPolicy> findByTenantAndVersion(String tenantId, int version);

  List<RiskPolicy> listByTenant(String tenantId);

  /** Próxima versão livre deste tenant (1 quando ele ainda não tem nenhuma). */
  int nextVersion(String tenantId);

  void activate(UUID id, String activatedBy, Instant when);

  void archive(UUID id, Instant when);
}
