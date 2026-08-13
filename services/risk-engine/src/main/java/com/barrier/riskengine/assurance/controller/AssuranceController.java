package com.barrier.riskengine.assurance.controller;

import com.barrier.riskengine.assurance.client.BiometricSubmission;
import com.barrier.riskengine.assurance.client.DocumentSubmission;
import com.barrier.riskengine.assurance.client.DocumentVerificationResult;
import com.barrier.riskengine.assurance.controller.dto.AssuranceCheckResponse;
import com.barrier.riskengine.assurance.controller.dto.ConsentRequest;
import com.barrier.riskengine.assurance.controller.dto.SubmitBiometricRequest;
import com.barrier.riskengine.assurance.controller.dto.SubmitDocumentRequest;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.service.AssuranceService;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Submissão de documentoscopia e biometria — a porta de entrada para {@code AssuranceService}.
 *
 * <p><b>Este endpoint é o perímetro de segurança do módulo assurance.</b> {@code AssuranceCheck}
 * carrega {@code subjectId}+{@code tenantId}, e a jusante {@code AssuranceReassessmentTrigger}
 * usa esse par para submeter uma reavaliação — que cria o vínculo tenant↔subject
 * ({@code AssessmentService.create} → {@code SubjectService.link}) se ainda não existir. Se este
 * controller deixasse nascer um {@code AssuranceCheck} com {@code subjectId} de um cliente e
 * {@code tenantId} de outro parceiro, o trigger criaria esse vínculo e produziria um {@code
 * Assessment} vazando documento e nome de cliente alheio pelo {@code GET
 * /v1/assessments/{id}} — o vazamento que a migration V024 e o ADR-0011 existem para fechar.
 *
 * <p>Por isso o subject é resolvido pelo documento <b>exigindo vínculo do tenant</b>, exatamente
 * como {@code SubjectProfileController}/{@code SubjectController} já fazem — reusado aqui, não
 * reescrito. Sem vínculo, {@code SubjectService.getForTenant} lança {@code
 * SubjectNotFoundException}, traduzida para 404 (nunca 403: 403 confirmaria que o cliente existe
 * em outro parceiro, o mesmo vazamento por outra porta).
 */
@RestController
@RequestMapping("/v1/subjects")
public class AssuranceController {

  private final SubjectService subjectService;
  private final AssuranceService assuranceService;

  public AssuranceController(SubjectService subjectService, AssuranceService assuranceService) {
    this.subjectService = subjectService;
    this.assuranceService = assuranceService;
  }

  /** Submete documentoscopia. O consentimento é obrigatório — ausência vira 400 no service. */
  @PostMapping("/{document}/assurance/document")
  public ResponseEntity<AssuranceCheckResponse> submitDocument(
      AuthenticatedTenant tenant,
      @PathVariable String document,
      @RequestBody SubmitDocumentRequest request) {
    Subject subject = resolveSubject(tenant.id(), document);
    AssuranceConsent consent = toConsent(request.consent());
    DocumentSubmission submission =
        new DocumentSubmission(
            request.captureReference(), request.documentType(), request.submittedHash());
    DocumentVerificationResult result =
        assuranceService.verifyDocument(subject.id(), tenant.id(), submission, consent);
    return ResponseEntity.ok(AssuranceCheckResponse.of(result.check()));
  }

  /** Submete biometria facial com prova de vida. Mesma exigência de consentimento. */
  @PostMapping("/{document}/assurance/biometric")
  public ResponseEntity<AssuranceCheckResponse> submitBiometric(
      AuthenticatedTenant tenant,
      @PathVariable String document,
      @RequestBody SubmitBiometricRequest request) {
    Subject subject = resolveSubject(tenant.id(), document);
    AssuranceConsent consent = toConsent(request.consent());
    BiometricSubmission submission =
        new BiometricSubmission(
            request.selfieReference(), request.documentFaceReference(), request.submittedHash());
    AssuranceCheck check =
        assuranceService.verifyBiometrics(subject.id(), tenant.id(), submission, consent);
    return ResponseEntity.ok(AssuranceCheckResponse.of(check));
  }

  /**
   * Mesma resolução de {@code SubjectProfileController}/{@code SubjectController}: documento →
   * tipo pelo tamanho, subject buscado com vínculo do tenant exigido. É o gate deste controller —
   * ver Javadoc da classe.
   */
  private Subject resolveSubject(String tenantId, String document) {
    String digits = document.replaceAll("\\D", "");
    String documentType =
        switch (digits.length()) {
          case 11 -> "CPF";
          case 14 -> "CNPJ";
          default -> throw new IllegalArgumentException("Documento inválido");
        };
    return subjectService.getForTenant(tenantId, documentType, digits);
  }

  private static AssuranceConsent toConsent(ConsentRequest request) {
    return request == null
        ? null
        : new AssuranceConsent(request.reference(), request.purpose(), request.grantedAt());
  }
}
