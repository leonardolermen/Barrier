package com.barrier.riskengine.assessment.controller;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints públicos da Risk Engine. */
@RestController
@RequestMapping("/v1/assessments")
public class AssessmentController {

  private final AssessmentService service;

  public AssessmentController(AssessmentService service) {
    this.service = service;
  }

  /** Submete uma avaliação. Responde 202 (aceita para processamento assíncrono). */
  @PostMapping
  public ResponseEntity<AssessmentResponse> submit(@Valid @RequestBody SubmitAssessmentRequest req) {
    Assessment created =
        service.submit(new SubmitAssessmentCommand(req.documentType(), req.document(), req.name()));
    return ResponseEntity.accepted()
        .location(URI.create("/v1/assessments/" + created.id().asString()))
        .body(AssessmentDtoMapper.toResponse(created));
  }

  /** Consulta o status/resultado de uma avaliação. */
  @GetMapping("/{id}")
  public ResponseEntity<AssessmentResponse> get(@PathVariable String id) {
    Assessment assessment = service.get(AssessmentId.of(id));
    return ResponseEntity.ok(AssessmentDtoMapper.toResponse(assessment));
  }
}
