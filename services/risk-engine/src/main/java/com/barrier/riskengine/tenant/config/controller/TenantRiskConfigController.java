package com.barrier.riskengine.tenant.config.controller;

import com.barrier.riskengine.tenant.config.domain.TenantRiskConfigEntry;
import com.barrier.riskengine.tenant.config.repository.TenantRiskConfigRepository;
import com.barrier.riskengine.tenant.config.validation.TenantRiskConfigValidator;
import com.barrier.riskengine.tenant.domain.Tenant;
import com.barrier.riskengine.tenant.service.TenantService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestão de overrides de risco por tenant — operação interna/administrativa (não é self-service
 * do parceiro): permitir que o próprio tenant relaxe seus controles de risco é o risco que a
 * allowlist de {@link TenantRiskConfigValidator} existe para evitar. Sem gate de admin-auth
 * dedicado ainda (pré-auth do projeto todo — ver {@link TenantService}); restringir por rede/
 * ambiente até existir autenticação de admin formal.
 */
@RestController
@RequestMapping("/v1/tenants/{tenantId}/risk-config")
public class TenantRiskConfigController {

  private final TenantService tenantService;
  private final TenantRiskConfigRepository repository;
  private final TenantRiskConfigValidator validator;

  public TenantRiskConfigController(
      TenantService tenantService,
      TenantRiskConfigRepository repository,
      TenantRiskConfigValidator validator) {
    this.tenantService = tenantService;
    this.repository = repository;
    this.validator = validator;
  }

  /** Cria ou atualiza o override de um parâmetro. Rejeita regra/parâmetro fora da allowlist. */
  @PutMapping
  public ResponseEntity<RiskConfigEntryResponse> upsert(
      @PathVariable String tenantId, @RequestBody UpsertRiskConfigRequest request) {
    Tenant tenant = tenantService.resolve(tenantId);
    validator.validate(request.ruleCode(), request.paramKey(), request.paramValue());
    TenantRiskConfigEntry saved =
        repository.upsert(
            tenant.id(),
            request.ruleCode(),
            request.paramKey(),
            request.paramValue(),
            request.updatedBy());
    return ResponseEntity.ok(RiskConfigEntryResponse.override(saved));
  }

  /** Config efetiva do tenant: default global para todo parâmetro configurável, sobreposto pelos overrides existentes. */
  @GetMapping
  public ResponseEntity<List<RiskConfigEntryResponse>> effective(@PathVariable String tenantId) {
    Tenant tenant = tenantService.resolve(tenantId);
    Map<String, TenantRiskConfigEntry> overrides = new HashMap<>();
    for (TenantRiskConfigEntry entry : repository.findByTenant(tenant.id())) {
      overrides.put(entry.ruleCode() + "/" + entry.paramKey(), entry);
    }

    List<RiskConfigEntryResponse> effective = new ArrayList<>();
    for (String ruleCode : validator.ruleCodes()) {
      validator
          .defaultsOf(ruleCode)
          .forEach(
              (paramKey, defaultValue) -> {
                TenantRiskConfigEntry override = overrides.get(ruleCode + "/" + paramKey);
                effective.add(
                    override != null
                        ? RiskConfigEntryResponse.override(override)
                        : RiskConfigEntryResponse.fromDefault(ruleCode, paramKey, defaultValue));
              });
    }
    return ResponseEntity.ok(effective);
  }
}
