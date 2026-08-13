package com.barrier.riskengine.tenant.config.controller;

import com.barrier.riskengine.tenant.config.controller.dto.UpsertRiskConfigRequest;
import com.barrier.riskengine.tenant.config.controller.dto.RiskConfigEntryResponse;

import com.barrier.riskengine.tenant.config.domain.TenantRiskConfigEntry;
import com.barrier.riskengine.tenant.config.service.TenantRiskConfigAdminService;
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
 * allowlist de {@link TenantRiskConfigValidator} existe para evitar.
 *
 * <p>Protegido por {@code X-Admin-Key} ({@link com.barrier.riskengine.web.AdminApiKeyFilter}).
 * O tenant alvo vem do <b>path</b>, e não do {@code X-Client-Id}, de propósito: é o admin operando
 * sobre a configuração de um parceiro. Isso só é seguro porque o filtro prova que quem chama é o
 * admin — antes dele, qualquer chamador editava a calibragem de qualquer tenant.
 */
@RestController
@RequestMapping("/v1/tenants/{tenantId}/risk-config")
public class TenantRiskConfigController {

  private final TenantService tenantService;
  private final TenantRiskConfigAdminService configService;
  private final TenantRiskConfigValidator validator;

  public TenantRiskConfigController(
      TenantService tenantService,
      TenantRiskConfigAdminService configService,
      TenantRiskConfigValidator validator) {
    this.tenantService = tenantService;
    this.configService = configService;
    this.validator = validator;
  }

  /** Cria ou atualiza o override de um parâmetro. Rejeita regra/parâmetro fora da allowlist. */
  @PutMapping
  public ResponseEntity<RiskConfigEntryResponse> upsert(
      @PathVariable String tenantId, @RequestBody UpsertRiskConfigRequest request) {
    Tenant tenant = tenantService.resolve(tenantId);
    TenantRiskConfigEntry saved =
        configService.upsert(
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
    for (TenantRiskConfigEntry entry : configService.findByTenant(tenant.id())) {
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
