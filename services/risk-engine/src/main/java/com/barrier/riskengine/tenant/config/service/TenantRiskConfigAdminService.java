package com.barrier.riskengine.tenant.config.service;

import com.barrier.riskengine.policy.ParamAuthorship;
import com.barrier.riskengine.policy.PolicyProvenance;
import com.barrier.riskengine.tenant.config.domain.TenantRiskConfigEntry;
import com.barrier.riskengine.tenant.config.repository.TenantRiskConfigRepository;
import com.barrier.riskengine.tenant.config.validation.TenantRiskConfigValidator;
import java.time.Instant;
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

  /**
   * Quem definiu o parâmetro que a regra usou naquele instante — o portão do módulo para o replay
   * de decisão.
   *
   * <p>Note o que este método <b>não</b> faz: não reconstrói o valor efetivo. Esse já está gravado
   * junto da decisão em {@code evaluated_json}, para toda regra que rodou, inclusive as que
   * passaram. Reconstruí-lo daqui criaria uma segunda fonte para o mesmo fato — e o projeto já pagou
   * o preço de duas cópias de uma verdade divergirem. O que falta é a autoria, e é só isso que sai.
   *
   * <p>Três casos, na mesma disciplina de {@code RiskRuleRegistryService.stateAsOf}: override em
   * vigor ({@code TENANT_OVERRIDE}), nenhum histórico para a chave ({@code GLOBAL_DEFAULT} — o valor
   * veio do default, que é código, e a autoria dele é o {@code ENGINE_VERSION}), e histórico
   * inteiramente posterior ({@code UNKNOWN}).
   */
  public ParamAuthorship authorshipAsOf(
      String tenantId, String ruleCode, String paramKey, String effectiveValue, Instant at) {
    return repository
        .authorshipAsOf(tenantId, ruleCode, paramKey, at)
        .orElseGet(
            () ->
                repository.hasAnyHistory(tenantId, ruleCode, paramKey)
                    ? new ParamAuthorship(
                        paramKey,
                        ParamAuthorship.ParamSource.UNKNOWN,
                        effectiveValue,
                        null,
                        null,
                        PolicyProvenance.UNKNOWN_BEFORE_HISTORY)
                    : new ParamAuthorship(
                        paramKey,
                        ParamAuthorship.ParamSource.GLOBAL_DEFAULT,
                        effectiveValue,
                        null,
                        null,
                        PolicyProvenance.UNCHANGED_SINCE_SEED));
  }

  /** Linha do tempo de overrides do tenant, da alteração mais recente para a mais antiga. */
  public List<ParamAuthorship> history(String tenantId) {
    return repository.history(tenantId);
  }
}
