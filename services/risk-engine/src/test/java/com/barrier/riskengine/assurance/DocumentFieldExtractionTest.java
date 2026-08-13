package com.barrier.riskengine.assurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assurance.client.DocumentSubmission;
import com.barrier.riskengine.assurance.client.DocumentVerificationResult;
import com.barrier.riskengine.assurance.client.ExtractedDocumentFields;
import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import com.barrier.riskengine.assurance.service.AssuranceRecordedListener;
import com.barrier.riskengine.assurance.service.AssuranceService;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.rule.IdentityAssuranceRiskRule;
import com.barrier.riskengine.risk.rule.context.AssuranceSummary;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.service.FieldVerificationService;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Campos lidos do documento viram verificação de cadastro (nascimento que confere) ou fator de
 * risco explicável (nome/documento/nascimento que divergem do declarado) — nunca são gravados
 * direto no {@code SubjectProfile}.
 */
class DocumentFieldExtractionTest {

  private static final UUID SUBJECT = UUID.randomUUID();
  private static final String TENANT = "t1";

  private final DocumentVerificationProvider documentProvider =
      Mockito.mock(DocumentVerificationProvider.class);
  private final BiometricVerificationProvider biometricProvider =
      Mockito.mock(BiometricVerificationProvider.class);
  private final AssuranceCheckRepository repository = Mockito.mock(AssuranceCheckRepository.class);
  private final SubjectProfileService subjectProfileService =
      Mockito.mock(SubjectProfileService.class);
  private final SubjectService subjectService = Mockito.mock(SubjectService.class);
  private final FieldVerificationService fieldVerificationService =
      Mockito.mock(FieldVerificationService.class);

  private final AssuranceService service =
      new AssuranceService(
          documentProvider,
          biometricProvider,
          repository,
          List.<AssuranceRecordedListener>of(),
          subjectProfileService,
          subjectService,
          fieldVerificationService);

  private final IdentityAssuranceRiskRule rule = new IdentityAssuranceRiskRule(600, 100, 200, 3, 300);

  private static DocumentSubmission submissao() {
    return new DocumentSubmission("ref", "RG", "hash");
  }

  private static AssuranceConsent consentimento() {
    return new AssuranceConsent("ref-1", "EKYC", Instant.now());
  }

  private static AssuranceCheck checkAprovado() {
    return new AssuranceCheck(
        UUID.randomUUID(),
        SUBJECT,
        TENANT,
        AssuranceKind.DOCUMENT,
        AssuranceOutcome.PASS,
        97,
        "provedor",
        "doc-ref-1",
        "modelo/1.0",
        "hash",
        "documentoscopia ok",
        Instant.now(),
        null);
  }

  private static SubjectProfile perfil(LocalDate nascimento) {
    SubjectProfile blank = SubjectProfile.blank(SUBJECT, TENANT);
    return new SubjectProfile(
        blank.id(),
        SUBJECT,
        TENANT,
        nascimento,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        blank.createdAt(),
        blank.updatedAt());
  }

  private static Subject subject(String nome, String documento) {
    return new Subject(SUBJECT, "CPF", documento, nome, Instant.now());
  }

  private static RiskContext contexto(AssuranceCheck documentCheck) {
    return new RiskContext(
        "a1", TENANT, null, null, null, null, new AssuranceSummary(documentCheck, null, 0));
  }

  /** 1. Nascimento extraído igual ao declarado -> vira FieldVerification method=DOCUMENT. */
  @Test
  void nascimentoIgualAoDeclaradoViraVerificacaoDeCadastro() {
    LocalDate nascimento = LocalDate.of(1990, 5, 20);
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(
            new DocumentVerificationResult(
                checkAprovado(), new ExtractedDocumentFields("MARIA SILVA", "12345678900", nascimento)));
    when(subjectProfileService.find(SUBJECT, TENANT)).thenReturn(perfil(nascimento));
    when(subjectService.findById(SUBJECT, TENANT)).thenReturn(subject("MARIA SILVA", "12345678900"));

    service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    ArgumentCaptor<LocalDate> declaradoCaptor = ArgumentCaptor.forClass(LocalDate.class);
    ArgumentCaptor<LocalDate> extraidoCaptor = ArgumentCaptor.forClass(LocalDate.class);
    ArgumentCaptor<String> evidenceCaptor = ArgumentCaptor.forClass(String.class);
    Mockito.verify(fieldVerificationService)
        .recordBirthDateFromDocument(
            org.mockito.ArgumentMatchers.eq(SUBJECT),
            org.mockito.ArgumentMatchers.eq(TENANT),
            declaradoCaptor.capture(),
            extraidoCaptor.capture(),
            evidenceCaptor.capture());
    assertThat(declaradoCaptor.getValue()).isEqualTo(nascimento);
    assertThat(extraidoCaptor.getValue()).isEqualTo(nascimento);
    assertThat(evidenceCaptor.getValue()).isEqualTo("doc-ref-1");
  }

  /** 2. Nascimento extraído diferente do declarado -> não verifica, e vira fator de risco. */
  @Test
  void nascimentoDivergenteNaoVerificaEViraFatorDeRisco() {
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(
            new DocumentVerificationResult(
                checkAprovado(),
                new ExtractedDocumentFields(
                    "MARIA SILVA", "12345678900", LocalDate.of(1991, 5, 20))));
    when(subjectProfileService.find(SUBJECT, TENANT)).thenReturn(perfil(LocalDate.of(1990, 5, 20)));
    when(subjectService.findById(SUBJECT, TENANT)).thenReturn(subject("MARIA SILVA", "12345678900"));
    ArgumentCaptor<AssuranceCheck> persistedCaptor = ArgumentCaptor.forClass(AssuranceCheck.class);

    service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    Mockito.verify(fieldVerificationService, Mockito.never())
        .recordBirthDateFromDocument(any(), any(), any(), any(), any());
    Mockito.verify(repository).save(persistedCaptor.capture());
    AssuranceCheck persisted = persistedCaptor.getValue();
    assertThat(persisted.detail()).contains(AssuranceCheck.CADASTRO_DIVERGENCE_MARKER);

    RiskContext contexto = contexto(persisted);
    var resultado = rule.evaluate(contexto);
    assertThat(resultado.triggered()).isTrue();
    assertThat(resultado.score()).isEqualTo(300);
    assertThat(resultado.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    // PII: o fator diz QUE divergiu, nunca os valores declarado/extraído.
    assertThat(resultado.evidences()).noneMatch(e -> e.contains("1990") || e.contains("1991"));
  }

  /** 3. Nome extraído diferente do declarado -> fator de risco (nascimento à parte permanece). */
  @Test
  void nomeDivergenteViraFatorDeRisco() {
    LocalDate nascimento = LocalDate.of(1990, 5, 20);
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(
            new DocumentVerificationResult(
                checkAprovado(),
                new ExtractedDocumentFields("OUTRO NOME", "12345678900", nascimento)));
    when(subjectProfileService.find(SUBJECT, TENANT)).thenReturn(perfil(nascimento));
    when(subjectService.findById(SUBJECT, TENANT)).thenReturn(subject("MARIA SILVA", "12345678900"));
    ArgumentCaptor<AssuranceCheck> persistedCaptor = ArgumentCaptor.forClass(AssuranceCheck.class);

    service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    Mockito.verify(repository).save(persistedCaptor.capture());
    AssuranceCheck persisted = persistedCaptor.getValue();
    assertThat(persisted.detail()).contains(AssuranceCheck.CADASTRO_DIVERGENCE_MARKER);
    assertThat(persisted.detail()).doesNotContain("OUTRO NOME").doesNotContain("MARIA SILVA");

    var resultado = rule.evaluate(contexto(persisted));
    assertThat(resultado.triggered()).isTrue();
    assertThat(resultado.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
  }

  /** 4. Documento reprovado (extracted == null) -> não grava nada, não quebra. */
  @Test
  void documentoReprovadoNaoExtraiNadaENaoQuebra() {
    AssuranceCheck falhou =
        new AssuranceCheck(
            UUID.randomUUID(),
            SUBJECT,
            TENANT,
            AssuranceKind.DOCUMENT,
            AssuranceOutcome.FAIL,
            10,
            "provedor",
            "doc-ref-2",
            "modelo/1.0",
            "hash",
            "adulterado",
            Instant.now(),
            null);
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(new DocumentVerificationResult(falhou, null));

    DocumentVerificationResult resultado =
        service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    assertThat(resultado.extracted()).isNull();
    Mockito.verifyNoInteractions(subjectProfileService, subjectService, fieldVerificationService);
    Mockito.verify(repository).save(any(AssuranceCheck.class));
  }
}
