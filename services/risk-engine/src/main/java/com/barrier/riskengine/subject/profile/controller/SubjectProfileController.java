package com.barrier.riskengine.subject.profile.controller;

import com.barrier.riskengine.subject.profile.controller.dto.UpdateProfileRequest;
import com.barrier.riskengine.subject.profile.controller.dto.ProfileResponse;

import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.profile.domain.RegistrationCompleteness;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.service.FieldVerificationService;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import com.barrier.riskengine.subject.service.SubjectService;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cadastro (CMN 4.753) de um subject, escopado por tenant — mesma regra de visibilidade de
 * {@link com.barrier.riskengine.subject.controller.SubjectController}: sem vínculo, 404.
 */
@RestController
@RequestMapping("/v1/subjects")
public class SubjectProfileController {

  private final SubjectService subjectService;
  private final SubjectProfileService profileService;
  private final FieldVerificationService verificationService;

  public SubjectProfileController(
      SubjectService subjectService,
      SubjectProfileService profileService,
      FieldVerificationService verificationService) {
    this.subjectService = subjectService;
    this.profileService = profileService;
    this.verificationService = verificationService;
  }

  /** Cria ou atualiza (parcialmente) o cadastro do subject. Cadastro é progressivo. */
  @PutMapping("/{document}/profile")
  public ResponseEntity<ProfileResponse> update(
      AuthenticatedTenant tenant,
      @PathVariable String document,
      @RequestBody UpdateProfileRequest request) {
    String digits = document.replaceAll("\\D", "");
    String documentType =
        switch (digits.length()) {
          case 11 -> "CPF";
          case 14 -> "CNPJ";
          default -> throw new IllegalArgumentException("Documento inválido");
        };
    Subject subject = subjectService.getForTenant(tenant.id(), documentType, digits);

    SubjectProfile profile = profileService.upsert(subject.id(), tenant.id(), request.toPatch());
    // A resposta precisa incluir as lacunas de verificação, não só as de preenchimento: senão o
    // parceiro lê "cadastro completo" aqui e recebe SOLICITAR_DOCUMENTO na avaliação, sem nada que
    // explique a contradição.
    RegistrationCompleteness completeness =
        RegistrationCompleteness.evaluate(
            documentType,
            profile,
            verificationService.verifiedFields(subject.id(), tenant.id(), profile));
    return ResponseEntity.ok(ProfileResponse.of(completeness));
  }
}
