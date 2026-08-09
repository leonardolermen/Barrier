package com.barrier.riskengine.assessment.controller;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints públicos da Risk Engine. O {@link AuthenticatedTenant} vem da credencial validada pelo
 * {@code TenantAuthenticationFilter} — o controller não tem como obtê-lo de outro lugar.
 */
@RestController
@RequestMapping("/v1/assessments")
public class AssessmentController {

  private final AssessmentService service;

  public AssessmentController(AssessmentService service) {
    this.service = service;
  }

  /** Submete uma avaliação. Responde 202 (aceita para processamento assíncrono). */
  @PostMapping
  public ResponseEntity<AssessmentResponse> submit(
      AuthenticatedTenant tenant, @Valid @RequestBody SubmitAssessmentRequest req) {
    Assessment created =
        service.submit(
            new SubmitAssessmentCommand(tenant.id(), req.documentType(), req.document(), req.name()));
    return ResponseEntity.accepted()
        .location(URI.create("/v1/assessments/" + created.id().asString()))
        .body(AssessmentDtoMapper.toResponse(created));
  }

  /** Consulta o status/resultado de uma avaliação (apenas do próprio tenant). */
  @GetMapping("/{id}")
  public ResponseEntity<AssessmentResponse> get(AuthenticatedTenant tenant, @PathVariable String id) {
    Assessment assessment = service.get(AssessmentId.of(id), tenant.id());
    return ResponseEntity.ok(AssessmentDtoMapper.toResponse(assessment));
  }

  /**
   * Decisão humana de uma avaliação em revisão (EDD). Só o tenant dono pode decidir.
   *
   * <p>{@code reviewedBy} segue sendo texto informado pelo chamador — a credencial identifica o
   * sistema cliente, não a pessoa. A trilha registra também qual chave decidiu, o que limita a
   * atribuição a um portador conhecido e revogável; identidade por operador depende de
   * autenticação de usuário, ainda não implementada.
   */
  @PostMapping("/{id}/decision")
  public ResponseEntity<AssessmentResponse> decide(
      AuthenticatedTenant tenant,
      @PathVariable String id,
      @Valid @RequestBody ReviewDecisionRequest req) {
    boolean approve = req.decision() == ReviewDecisionRequest.Decision.APPROVE;
    Assessment decided =
        service.decide(
            AssessmentId.of(id), tenant.id(), approve, req.reviewedBy(), tenant.keyName(), req.reason());
    return ResponseEntity.ok(AssessmentDtoMapper.toResponse(decided));
  }
}
