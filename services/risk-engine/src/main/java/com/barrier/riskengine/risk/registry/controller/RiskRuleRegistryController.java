package com.barrier.riskengine.risk.registry.controller;

import com.barrier.riskengine.risk.registry.controller.dto.UpsertRiskRuleRegistryRequest;
import com.barrier.riskengine.risk.registry.controller.dto.RiskRuleRegistryEntryResponse;

import com.barrier.riskengine.policy.RegistryPolicyState;
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

  /**
   * Linha do tempo de uma regra: cada alteração, com autoria, da mais recente para a mais antiga.
   *
   * <p>Responde "quem desligou esta regra, e quando" — que a trilha da avaliação não conta. Uma
   * regra desligada por uma semana e religada aparece em {@code evaluated_json} como suprimida, sem
   * nome nem data.
   *
   * <p>⚠️ A lista termina na primeira alteração <b>registrada</b>: o estado anterior a ela é o da
   * semente da migration e não foi gravado, porque a V033 guarda o estado novo de cada mudança.
   */
  @GetMapping("/{ruleCode}/history")
  public ResponseEntity<List<RegistryPolicyState>> history(@PathVariable String ruleCode) {
    return ResponseEntity.ok(service.history(ruleCode));
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
            request.validUntil(),
            request.updatedBy());
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
