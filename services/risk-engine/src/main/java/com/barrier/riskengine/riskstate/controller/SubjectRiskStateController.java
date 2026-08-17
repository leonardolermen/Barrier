package com.barrier.riskengine.riskstate.controller;

import com.barrier.commons.mask.Documents;
import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.subject.domain.DocumentTypeResolver;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.domain.SubjectNotFoundException;
import com.barrier.riskengine.subject.service.SubjectService;
import com.barrier.riskengine.riskstate.controller.dto.SubjectRiskStateResponse;
import com.barrier.riskengine.riskstate.domain.SubjectRiskState;
import com.barrier.riskengine.riskstate.service.SubjectRiskStateService;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Risco corrente do cliente, escopado por tenant.
 *
 * <p>Responde a pergunta que a trilha não responde sem varredura: <i>qual é o risco deste cliente
 * agora</i>. Subject sem vínculo com o tenant responde 404, igual ao {@code GET
 * /v1/subjects/{document}} — não vaza cliente de outra empresa.
 */
@RestController
@RequestMapping("/v1/subjects")
public class SubjectRiskStateController {

  private final SubjectService subjectService;
  private final SubjectRiskStateService riskStateService;
  private final AssessmentService assessmentService;

  public SubjectRiskStateController(
      SubjectService subjectService,
      SubjectRiskStateService riskStateService,
      AssessmentService assessmentService) {
    this.subjectService = subjectService;
    this.riskStateService = riskStateService;
    this.assessmentService = assessmentService;
  }

  @GetMapping("/{document}/risk-state")
  public ResponseEntity<SubjectRiskStateResponse> get(
      AuthenticatedTenant tenant, @PathVariable String document) {
    DocumentTypeResolver.Resolved resolved = DocumentTypeResolver.resolve(document);
    Subject subject =
        subjectService.getForTenant(tenant.id(), resolved.documentType(), resolved.digits());

    Optional<SubjectRiskState> state = riskStateService.find(subject.id(), tenant.id());
    if (state.isPresent()) {
      return ResponseEntity.ok(fromProjection(subject, state.get()));
    }
    // Fallback pela última avaliação concluída — mesmo desenho do score corrente do Mishmar. Cobre
    // o subject criado entre a migration da projeção e sua primeira avaliação nova; o backfill da
    // V041 já cobriu o histórico.
    return assessmentService
        .findLastCompleted(subject.id(), tenant.id())
        .map(assessment -> ResponseEntity.ok(fromAssessment(subject, assessment)))
        .orElseThrow(() -> new SubjectNotFoundException(resolved.digits()));
  }

  private static SubjectRiskStateResponse fromProjection(Subject subject, SubjectRiskState state) {
    return new SubjectRiskStateResponse(
        subject.id().toString(),
        subject.documentType(),
        Documents.mask(subject.document()),
        state.level().name(),
        state.score(),
        state.decision().name(),
        state.assessmentId().toString(),
        state.engineVersion(),
        state.evaluatedAt(),
        true);
  }

  private static SubjectRiskStateResponse fromAssessment(Subject subject, Assessment assessment) {
    return new SubjectRiskStateResponse(
        subject.id().toString(),
        subject.documentType(),
        Documents.mask(subject.document()),
        assessment.riskLevel().name(),
        null,
        assessment.status().name(),
        assessment.id().asString(),
        null,
        assessment.completedAt(),
        false);
  }
}
