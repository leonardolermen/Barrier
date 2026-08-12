package com.barrier.riskengine.subject.profile.controller;

import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.profile.controller.dto.ConfirmVerificationRequest;
import com.barrier.riskengine.subject.profile.controller.dto.VerificationChallengeResponse;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.domain.VerifiableField;
import com.barrier.riskengine.subject.profile.service.FieldVerificationService;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import com.barrier.riskengine.subject.service.SubjectService;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verificação de veracidade dos campos do cadastro, escopada por tenant como o resto do cadastro.
 *
 * <p>O canal do desafio sai do cadastro, nunca do corpo da requisição — ver
 * {@code FieldVerificationService.challenge}.
 */
@RestController
@RequestMapping("/v1/subjects/{document}/verifications")
public class FieldVerificationController {

  private final SubjectService subjectService;
  private final SubjectProfileService profileService;
  private final FieldVerificationService verificationService;

  public FieldVerificationController(
      SubjectService subjectService,
      SubjectProfileService profileService,
      FieldVerificationService verificationService) {
    this.subjectService = subjectService;
    this.profileService = profileService;
    this.verificationService = verificationService;
  }

  /** Dispara o código para o canal declarado. O código não volta na resposta, nem em dev. */
  @PostMapping("/{field}/challenge")
  public ResponseEntity<VerificationChallengeResponse> challenge(
      AuthenticatedTenant tenant, @PathVariable String document, @PathVariable String field) {
    Subject subject = resolve(tenant, document);
    SubjectProfile profile = profileService.find(subject.id(), tenant.id());
    UUID challengeId =
        verificationService.challenge(
            subject.id(), tenant.id(), parse(field), profile);
    return ResponseEntity.accepted().body(new VerificationChallengeResponse(challengeId.toString()));
  }

  /**
   * Confirma o código. Código errado responde 422, não 404 nem 400 detalhado: distinguir "não
   * existe desafio", "expirou" e "código errado" na resposta entregaria ao atacante exatamente o
   * sinal que ele precisa para saber onde insistir.
   */
  @PostMapping("/{field}/confirm")
  public ResponseEntity<Void> confirm(
      AuthenticatedTenant tenant,
      @PathVariable String document,
      @PathVariable String field,
      @RequestBody ConfirmVerificationRequest request) {
    Subject subject = resolve(tenant, document);
    boolean ok =
        verificationService.confirm(subject.id(), tenant.id(), parse(field), request.code());
    return ok
        ? ResponseEntity.noContent().build()
        : ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build();
  }

  private Subject resolve(AuthenticatedTenant tenant, String document) {
    String digits = document.replaceAll("\\D", "");
    String documentType =
        switch (digits.length()) {
          case 11 -> "CPF";
          case 14 -> "CNPJ";
          default -> throw new IllegalArgumentException("Documento inválido");
        };
    return subjectService.getForTenant(tenant.id(), documentType, digits);
  }

  private static VerifiableField parse(String field) {
    try {
      return VerifiableField.valueOf(field.toUpperCase().replace('-', '_'));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Campo não verificável: " + field);
    }
  }
}
