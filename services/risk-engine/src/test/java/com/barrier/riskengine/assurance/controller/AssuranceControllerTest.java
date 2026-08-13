package com.barrier.riskengine.assurance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assurance.client.DocumentVerificationResult;
import com.barrier.riskengine.assurance.client.ExtractedDocumentFields;
import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import com.barrier.riskengine.assurance.controller.dto.ConsentRequest;
import com.barrier.riskengine.assurance.controller.dto.SubmitBiometricRequest;
import com.barrier.riskengine.assurance.controller.dto.SubmitDocumentRequest;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import com.barrier.riskengine.assurance.service.AssuranceService;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.domain.SubjectNotFoundException;
import com.barrier.riskengine.subject.repository.interfaces.SubjectRepository;
import com.barrier.riskengine.subject.service.SubjectService;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import com.barrier.riskengine.tenant.domain.Tenant;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * O controller é o perímetro (CLAUDE.md/ADR-0011): resolve o subject pelo documento exigindo
 * vínculo do tenant, exatamente como {@code SubjectProfileController}. Testes unitários — sem
 * contexto Spring — com {@code SubjectService}/{@code AssuranceService} reais montados sobre
 * dependências mockadas, para provar o comportamento fim-a-fim do controller sem MockMvc.
 */
class AssuranceControllerTest {

  private static final String TENANT_ID = "tenant-a";
  private static final String DOCUMENT = "52998224725"; // CPF válido, só dígitos

  private SubjectRepository subjectRepository;
  private AssuranceCheckRepository assuranceRepository;
  private DocumentVerificationProvider documentProvider;
  private BiometricVerificationProvider biometricProvider;
  private AssuranceController controller;

  @BeforeEach
  void setUp() {
    subjectRepository = Mockito.mock(SubjectRepository.class);
    assuranceRepository = Mockito.mock(AssuranceCheckRepository.class);
    documentProvider = Mockito.mock(DocumentVerificationProvider.class);
    biometricProvider = Mockito.mock(BiometricVerificationProvider.class);

    SubjectService subjectService = new SubjectService(subjectRepository);
    AssuranceService assuranceService =
        new AssuranceService(
            documentProvider, biometricProvider, assuranceRepository, List.of());
    controller = new AssuranceController(subjectService, assuranceService);
  }

  private AuthenticatedTenant tenant() {
    return new AuthenticatedTenant(new Tenant(TENANT_ID, "Tenant A", true), "key-1");
  }

  private Subject linkedSubject() {
    Subject subject = Subject.create("CPF", DOCUMENT, "Fulano de Tal");
    when(subjectRepository.findByDocument("CPF", DOCUMENT)).thenReturn(Optional.of(subject));
    when(subjectRepository.isLinked(TENANT_ID, subject.id())).thenReturn(true);
    return subject;
  }

  private SubmitDocumentRequest documentRequestWithConsent() {
    return new SubmitDocumentRequest(
        "capture-ref",
        "RG",
        "hash-abc",
        new ConsentRequest("consent-ref", "verificação de identidade", Instant.now().minusSeconds(60)));
  }

  @Test
  void consentimentoAusenteRecusaAntesDeChamarProvedor() {
    linkedSubject();
    SubmitDocumentRequest request =
        new SubmitDocumentRequest("capture-ref", "RG", "hash-abc", null);

    assertThatThrownBy(() -> controller.submitDocument(tenant(), DOCUMENT, request))
        .isInstanceOf(IllegalArgumentException.class);

    verify(documentProvider, never()).verify(any(), any(), any());
  }

  @Test
  void subjectSemVinculoDevolveNotFound() {
    when(subjectRepository.findByDocument("CPF", DOCUMENT)).thenReturn(Optional.empty());
    SubmitDocumentRequest request = documentRequestWithConsent();

    assertThatThrownBy(() -> controller.submitDocument(tenant(), DOCUMENT, request))
        .isInstanceOf(SubjectNotFoundException.class);

    verify(documentProvider, never()).verify(any(), any(), any());
  }

  @Test
  void submissaoValidaDevolve200ComDesfechoEProvedor() {
    Subject subject = linkedSubject();
    AssuranceCheck check =
        new AssuranceCheck(
            UUID.randomUUID(),
            subject.id(),
            TENANT_ID,
            AssuranceKind.DOCUMENT,
            AssuranceOutcome.PASS,
            97,
            "stub-document-provider",
            "prov-ref-123",
            "v1.0.0",
            "hash-abc",
            null,
            Instant.now(),
            null);
    ExtractedDocumentFields extracted =
        new ExtractedDocumentFields("Fulano de Tal", DOCUMENT, null);
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(new DocumentVerificationResult(check, extracted));

    ResponseEntity<?> response =
        controller.submitDocument(tenant(), DOCUMENT, documentRequestWithConsent());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
  }

  @Test
  void respostaNaoContemCamposExtraidosDoDocumento() {
    Subject subject = linkedSubject();
    AssuranceCheck check =
        new AssuranceCheck(
            UUID.randomUUID(),
            subject.id(),
            TENANT_ID,
            AssuranceKind.DOCUMENT,
            AssuranceOutcome.PASS,
            97,
            "stub-document-provider",
            "prov-ref-123",
            "v1.0.0",
            "hash-abc",
            null,
            Instant.now(),
            null);
    ExtractedDocumentFields extracted =
        new ExtractedDocumentFields("Fulano de Tal", "12345678900", null);
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(new DocumentVerificationResult(check, extracted));

    ResponseEntity<?> response =
        controller.submitDocument(tenant(), DOCUMENT, documentRequestWithConsent());

    String body = String.valueOf(response.getBody());
    assertThat(body).doesNotContain("Fulano de Tal");
    assertThat(body).doesNotContain("12345678900");
  }

  @Test
  void submissaoBiometricaValidaDevolve200() {
    Subject subject = linkedSubject();
    AssuranceCheck check =
        new AssuranceCheck(
            UUID.randomUUID(),
            subject.id(),
            TENANT_ID,
            AssuranceKind.BIOMETRIC,
            AssuranceOutcome.PASS,
            88,
            "stub-biometric-provider",
            "prov-ref-456",
            "v1.0.0",
            "hash-def",
            null,
            Instant.now(),
            null);
    when(biometricProvider.verify(any(), any(), any())).thenReturn(check);

    SubmitBiometricRequest request =
        new SubmitBiometricRequest(
            "selfie-ref",
            "doc-face-ref",
            "hash-def",
            new ConsentRequest(
                "consent-ref", "verificação de identidade", Instant.now().minusSeconds(60)));

    ResponseEntity<?> response = controller.submitBiometric(tenant(), DOCUMENT, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
