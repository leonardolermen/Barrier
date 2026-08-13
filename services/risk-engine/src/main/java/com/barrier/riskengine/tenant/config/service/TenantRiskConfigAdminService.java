package com.barrier.riskengine.tenant.config.service;

import com.barrier.riskengine.tenant.config.domain.TenantRiskConfigEntry;
import com.barrier.riskengine.tenant.config.repository.TenantRiskConfigRepository;
import com.barrier.riskengine.tenant.config.validation.TenantRiskConfigValidator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Escrita e leitura administrativa dos overrides de risco por tenant.
 *
 * <p>Separado de {@link TenantRiskConfigService} de propósito: aquela interface é o caminho de
 * <b>leitura</b> que as regras de risco usam durante a avaliação, e é o nome que a regra de
 * arquitetura {@code regras_fixas_nao_dependem_de_config_por_tenant} vigia — pendurar CRUD
 * administrativo nela enfraqueceria a própria regra.
 */
@Service
public class TenantRiskConfigAdminService {

  private final TenantRiskConfigRepository repository;
  private final TenantRiskConfigValidator validator;

  public TenantRiskConfigAdminService(
      TenantRiskConfigRepository repository, TenantRiskConfigValidator validator) {
    this.repository = repository;
    this.validator = validator;
  }

  /**
   * Cria ou atualiza um override, rejeitando regra/parâmetro fora da allowlist.
   *
   * <p>A validação roda aqui, e não na borda web, para que ela não dependa de quem chama: o
   * risco que ela existe para evitar é um parceiro relaxar os próprios controles de risco.
   */
  public TenantRiskConfigEntry upsert(
      String tenantId, String ruleCode, String paramKey, String paramValue, String updatedBy) {
    validator.validate(ruleCode, paramKey, paramValue);
    return repository.upsert(tenantId, ruleCode, paramKey, paramValue, updatedBy);
  }

  /** Overrides gravados para o tenant — sem os defaults globais. */
  public List<TenantRiskConfigEntry> findByTenant(String tenantId) {
    return repository.findByTenant(tenantId);
  }
}
