package com.barrier.riskengine.subject.profile.controller;

import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.profile.domain.RegistrationCompleteness;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import com.barrier.riskengine.subject.service.SubjectService;
import com.barrier.riskengine.tenant.domain.Tenant;
import com.barrier.riskengine.tenant.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cadastro (CMN 4.753) de um subject, escopado por tenant — mesma regra de visibilidade de
 * {@link com.barrier.riskengine.subject.controller.SubjectController}: sem vínculo, 404.
 */
@RestController
@RequestMapping("/v1/subjects")
public class SubjectProfileController {

  private static final String CLIENT_HEADER = "X-Client-Id";

  private final SubjectService subjectService;
  private final SubjectProfileService profileService;
  private final TenantService tenantService;

  public SubjectProfileController(
      SubjectService subjectService,
      SubjectProfileService profileService,
      TenantService tenantService) {
    this.subjectService = subjectService;
    this.profileService = profileService;
    this.tenantService = tenantService;
  }

  /** Cria ou atualiza (parcialmente) o cadastro do subject. Cadastro é progressivo. */
  @PutMapping("/{document}/profile")
  public ResponseEntity<ProfileResponse> update(
      @RequestHeader(name = CLIENT_HEADER, required = false) String clientId,
      @PathVariable String document,
      @RequestBody UpdateProfileRequest request) {
    Tenant tenant = tenantService.resolve(clientId);
    String digits = document.replaceAll("\\D", "");
    String documentType =
        switch (digits.length()) {
          case 11 -> "CPF";
          case 14 -> "CNPJ";
          default -> throw new IllegalArgumentException("Documento inválido");
        };
    Subject subject = subjectService.getForTenant(tenant.id(), documentType, digits);

    SubjectProfile profile = profileService.upsert(subject.id(), request.toPatch());
    RegistrationCompleteness completeness =
        RegistrationCompleteness.evaluate(documentType, profile);
    return ResponseEntity.ok(ProfileResponse.of(completeness, profile));
  }
}
