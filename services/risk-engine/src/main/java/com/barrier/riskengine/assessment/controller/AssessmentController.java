package com.barrier.riskengine.assessment.controller;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import com.barrier.riskengine.tenant.domain.Tenant;
import com.barrier.riskengine.tenant.service.TenantService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints públicos da Risk Engine. Toda operação é escopada por tenant (header X-Client-Id). */
@RestController
@RequestMapping("/v1/assessments")
public class AssessmentController {

  private static final String CLIENT_HEADER = "X-Client-Id";

  private final AssessmentService service;
  private final TenantService tenantService;

  public AssessmentController(AssessmentService service, TenantService tenantService) {
    this.service = service;
    this.tenantService = tenantService;
  }

  /** Submete uma avaliação. Responde 202 (aceita para processamento assíncrono). */
  @PostMapping
  public ResponseEntity<AssessmentResponse> submit(
      @RequestHeader(name = CLIENT_HEADER, required = false) String clientId,
      @Valid @RequestBody SubmitAssessmentRequest req) {
    Tenant tenant = tenantService.resolve(clientId);
    Assessment created =
        service.submit(
            new SubmitAssessmentCommand(
                tenant.id(),
                req.documentType(),
                req.document(),
                req.name(),
                req.ip(),
                req.deviceId()));
    return ResponseEntity.accepted()
        .location(URI.create("/v1/assessments/" + created.id().asString()))
        .body(AssessmentDtoMapper.toResponse(created));
  }

  /** Consulta o status/resultado de uma avaliação (apenas do próprio tenant). */
  @GetMapping("/{id}")
  public ResponseEntity<AssessmentResponse> get(
      @RequestHeader(name = CLIENT_HEADER, required = false) String clientId,
      @PathVariable String id) {
    Tenant tenant = tenantService.resolve(clientId);
    Assessment assessment = service.get(AssessmentId.of(id), tenant.id());
    return ResponseEntity.ok(AssessmentDtoMapper.toResponse(assessment));
  }

  /** Decisão humana de uma avaliação em revisão (EDD). Só o tenant dono pode decidir. */
  @PostMapping("/{id}/decision")
  public ResponseEntity<AssessmentResponse> decide(
      @RequestHeader(name = CLIENT_HEADER, required = false) String clientId,
      @PathVariable String id,
      @Valid @RequestBody ReviewDecisionRequest req) {
    Tenant tenant = tenantService.resolve(clientId);
    boolean approve = req.decision() == ReviewDecisionRequest.Decision.APPROVE;
    Assessment decided =
        service.decide(AssessmentId.of(id), tenant.id(), approve, req.reviewedBy(), req.reason());
    return ResponseEntity.ok(AssessmentDtoMapper.toResponse(decided));
  }
}
