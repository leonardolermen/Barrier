package com.barrier.riskengine.risk.registry.controller;

import com.barrier.riskengine.risk.registry.domain.RiskRuleCriticality;
import com.barrier.riskengine.risk.registry.service.RiskRuleRegistryService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestão do registry de regras de risco — kill switch/vigência global (todos os tenants), não
 * self-service do parceiro.
 *
 * <p>Protegido por {@code X-Admin-Key}
 * ({@link com.barrier.riskengine.web.AdminApiKeyFilter}); sem a chave em produção a aplicação nem
 * sobe. Além disso, as famílias regulatórias
 * ({@link com.barrier.riskengine.risk.registry.domain.RegulatoryRiskRules}) não podem ser
 * desabilitadas nem ter vigência limitada por esta API — nem com a chave correta.
 */
@RestController
@RequestMapping("/v1/risk-rules")
public class RiskRuleRegistryController {

  private final RiskRuleRegistryService service;

  public RiskRuleRegistryController(RiskRuleRegistryService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<List<RiskRuleRegistryEntryResponse>> findAll() {
    return ResponseEntity.ok(service.findAll().stream().map(RiskRuleRegistryEntryResponse::of).toList());
  }

  @PutMapping("/{ruleCode}")
  public ResponseEntity<RiskRuleRegistryEntryResponse> upsert(
      @PathVariable String ruleCode, @RequestBody UpsertRiskRuleRegistryRequest request) {
    validateCriticality(request.criticality());
    var saved =
        service.upsert(
            ruleCode,
            request.description(),
            request.criticality(),
            request.enabled(),
            request.validFrom(),
            request.validUntil());
    return ResponseEntity.ok(RiskRuleRegistryEntryResponse.of(saved));
  }

  private static void validateCriticality(String criticality) {
    try {
      RiskRuleCriticality.valueOf(criticality);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException("criticality inválida: " + criticality);
    }
  }
}
