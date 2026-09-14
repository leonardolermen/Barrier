package com.barrier.riskengine.tenant.config.repository;

import com.barrier.riskengine.policy.ParamAuthorship;
import com.barrier.riskengine.tenant.config.domain.TenantRiskConfigEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Repositório de domínio dos overrides de config de risco por tenant. */
public interface TenantRiskConfigRepository {

  Optional<TenantRiskConfigEntry> find(String tenantId, String ruleCode, String paramKey);

  List<TenantRiskConfigEntry> findByTenant(String tenantId);

  /**
   * O override em vigor para esta chave no instante {@code at}, com autoria. Vazio quando não há
   * entrada de histórico até ali — o chamador distingue "nunca houve override" de "houve, mas só
   * depois" com {@link #hasAnyHistory}.
   *
   * <p>Uma entrada com {@code param_value} nulo significa <b>override removido</b> (voltou ao default
   * global), e por isso também devolve vazio: naquele instante não havia override.
   */
  Optional<ParamAuthorship> authorshipAsOf(
      String tenantId, String ruleCode, String paramKey, Instant at);

  boolean hasAnyHistory(String tenantId, String ruleCode, String paramKey);

  /** Linha do tempo de overrides do tenant, da alteração mais recente para a mais antiga. */
  List<ParamAuthorship> history(String tenantId);

  /** Cria o override se não existir, ou atualiza o valor/autor de um já existente. */
  TenantRiskConfigEntry upsert(
      String tenantId, String ruleCode, String paramKey, String paramValue, String updatedBy);
}
