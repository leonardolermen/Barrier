package com.barrier.riskengine.riskpolicy.controller;

import com.barrier.riskengine.riskpolicy.controller.dto.ActivatePolicyRequest;
import com.barrier.riskengine.riskpolicy.controller.dto.CreatePolicyRequest;
import com.barrier.riskengine.riskpolicy.controller.dto.PolicyDtoMapper;
import com.barrier.riskengine.riskpolicy.controller.dto.PolicyResponse;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicy;
import com.barrier.riskengine.riskpolicy.service.RiskPolicyService;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ciclo de vida de política de risco custom, self-service: o parceiro cria, ativa e arquiva as
 * próprias versões. É self-service porque o invariante aditivo do {@code PolicyCompiler} garante
 * que uma regra custom só endurece a decisão -- diferente de {@code
 * TenantRiskConfigController}, que calibra parâmetro e pode afrouxar, por isso continua
 * administrativo.
 *
 * <p>O {@link AuthenticatedTenant} vem da credencial validada pelo {@code
 * TenantAuthenticationFilter}, nunca do caminho -- seguindo {@code BehaviorEventController}. Uma
 * versão de outro tenant responde 404, nunca 403: 403 confirmaria que a versão existe, e {@code
 * RiskPolicyService.require} já resolve por {@code (tenantId, version)}, então essa distinção
 * nunca chega até aqui para vazar.
 */
@RestController
@RequestMapping("/v1/policies")
public class RiskPolicyController {

  private final RiskPolicyService service;
  private final PolicyDtoMapper mapper;

  public RiskPolicyController(RiskPolicyService service, PolicyDtoMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  /**
   * Cria uma versão {@code DRAFT}. Compila as regras antes de gravar; falha vira 400.
   *
   * <p>{@code @ApiResponse(responseCode = "201")} não é decoração: sem ela o springdoc
   * documenta 200 para todo método que devolve {@code ResponseEntity}, porque não resolve
   * {@code ResponseEntity.status(...)} estaticamente -- o mesmo gap que {@code
   * AssessmentController.submit} e {@code BehaviorEventController.record} têm hoje (202
   * real, 200 documentado), fora do escopo desta task para corrigir. Aqui a resposta real
   * é 201, e o contrato precisa dizer isso.
   */
  @PostMapping
  @ApiResponse(responseCode = "201", description = "Versão DRAFT criada")
  public ResponseEntity<PolicyResponse> create(
      AuthenticatedTenant tenant, @Valid @RequestBody CreatePolicyRequest request) {
    RiskPolicy created =
        service.createDraft(
            tenant.id(), request.domain(), mapper.toDomain(request.rules()), request.createdBy());
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/v1/policies/" + created.version()))
        .body(mapper.toResponse(created));
  }

  /** Lista as versões do tenant autenticado. */
  @GetMapping
  public List<PolicyResponse> list(AuthenticatedTenant tenant) {
    return service.list(tenant.id()).stream().map(mapper::toResponse).toList();
  }

  /** Lê uma versão do tenant autenticado; inexistente ou de outro tenant responde 404. */
  @GetMapping("/{version}")
  public PolicyResponse get(AuthenticatedTenant tenant, @PathVariable int version) {
    return mapper.toResponse(service.get(tenant.id(), version));
  }

  /** Ativa uma versão {@code DRAFT}, arquivando a {@code ACTIVE} atual do domínio, se houver. */
  @PostMapping("/{version}/activate")
  public PolicyResponse activate(
      AuthenticatedTenant tenant,
      @PathVariable int version,
      @Valid @RequestBody ActivatePolicyRequest request) {
    return mapper.toResponse(service.activate(tenant.id(), version, request.activatedBy()));
  }

  /** Arquiva uma versão -- o kill switch de uma política custom é arquivar a versão ativa. */
  @PostMapping("/{version}/archive")
  public PolicyResponse archive(AuthenticatedTenant tenant, @PathVariable int version) {
    return mapper.toResponse(service.archive(tenant.id(), version));
  }
}
