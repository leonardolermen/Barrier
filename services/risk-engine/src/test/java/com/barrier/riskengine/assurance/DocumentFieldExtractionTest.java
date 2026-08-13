package com.barrier.riskengine.assurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.barrier.riskengine.assurance.domain.DivergentField;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import com.barrier.riskengine.assurance.service.AssuranceRecordedListener;
import com.barrier.riskengine.assurance.service.AssuranceService;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskResult;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Campos lidos do documento viram verificação de cadastro (nascimento que confere) ou fator de
 * risco explicável (nome/nascimento que divergem do declarado) — nunca são gravados direto no
 * {@code SubjectProfile}.
 *
 * <p>Documento (CPF/CNPJ) não entra nessa comparação de propósito: {@code
 * ExtractedDocumentFields.document} é o número do documento apresentado (RG, CNH...), grandeza
 * diferente do {@code Subject.document} (CPF/CNPJ, ADR-0011) — ver {@link DivergentField}.
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

  private final IdentityAssuranceRiskRule rule =
      new IdentityAssuranceRiskRule(600, 100, 200, 3, 300);

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
        Set.of(),
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
    when(subjectService.findById(SUBJECT, TENANT))
        .thenReturn(subject("MARIA SILVA", "12345678900"));

    DocumentVerificationResult resultado =
        service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    ArgumentCaptor<LocalDate> declaradoCaptor = ArgumentCaptor.forClass(LocalDate.class);
    ArgumentCaptor<LocalDate> extraidoCaptor = ArgumentCaptor.forClass(LocalDate.class);
    ArgumentCaptor<String> evidenceCaptor = ArgumentCaptor.forClass(String.class);
    verify(fieldVerificationService)
        .recordBirthDateFromDocument(
            eq(SUBJECT), eq(TENANT), declaradoCaptor.capture(), extraidoCaptor.capture(), evidenceCaptor.capture());
    assertThat(declaradoCaptor.getValue()).isEqualTo(nascimento);
    assertThat(extraidoCaptor.getValue()).isEqualTo(nascimento);
    assertThat(evidenceCaptor.getValue()).isEqualTo("doc-ref-1");
    assertThat(resultado.check().divergences()).isEmpty();
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
    when(subjectService.findById(SUBJECT, TENANT))
        .thenReturn(subject("MARIA SILVA", "12345678900"));
    ArgumentCaptor<AssuranceCheck> persistedCaptor = ArgumentCaptor.forClass(AssuranceCheck.class);

    service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    verify(fieldVerificationService, never())
        .recordBirthDateFromDocument(any(), any(), any(), any(), any());
    verify(repository).save(persistedCaptor.capture());
    AssuranceCheck persisted = persistedCaptor.getValue();
    assertThat(persisted.divergences()).containsExactly(DivergentField.BIRTH_DATE);
    // detail é a mensagem do provedor, e tem de sobreviver intacta — a divergência vive num
    // campo próprio, não concatenada nela.
    assertThat(persisted.detail()).isEqualTo("documentoscopia ok");

    RiskResult resultado = rule.evaluate(contexto(persisted));
    assertThat(resultado.triggered()).isTrue();
    assertThat(resultado.score()).isEqualTo(300);
    assertThat(resultado.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    // PII: o fator diz QUE divergiu, nunca os valores declarado/extraído.
    assertThat(resultado.evidences()).noneMatch(e -> e.contains("1990") || e.contains("1991"));
  }

  /** 3. Nome extraído diferente do declarado -> fator de risco (nascimento à parte permanece). */
  @Test
  void nomeDivergenteViraFatorDeRiscoEAindaAssimVerificaNascimento() {
    LocalDate nascimento = LocalDate.of(1990, 5, 20);
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(
            new DocumentVerificationResult(
                checkAprovado(), new ExtractedDocumentFields("OUTRO NOME", "12345678900", nascimento)));
    when(subjectProfileService.find(SUBJECT, TENANT)).thenReturn(perfil(nascimento));
    when(subjectService.findById(SUBJECT, TENANT))
        .thenReturn(subject("MARIA SILVA", "12345678900"));
    ArgumentCaptor<AssuranceCheck> persistedCaptor = ArgumentCaptor.forClass(AssuranceCheck.class);

    service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    verify(repository).save(persistedCaptor.capture());
    AssuranceCheck persisted = persistedCaptor.getValue();
    assertThat(persisted.divergences()).containsExactly(DivergentField.NAME);
    assertThat(persisted.detail()).isEqualTo("documentoscopia ok");
    // "à parte permanece": nome divergente não impede o nascimento (que bateu) de virar
    // verificação de cadastro — os dois campos são avaliados independentemente.
    verify(fieldVerificationService)
        .recordBirthDateFromDocument(SUBJECT, TENANT, nascimento, nascimento, "doc-ref-1");

    RiskResult resultado = rule.evaluate(contexto(persisted));
    assertThat(resultado.triggered()).isTrue();
    assertThat(resultado.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
  }

  /**
   * Normalização ignora pontuação e caixa — sem isso, "maria silva" batendo contra "MARIA SILVA"
   * (ou CPF pontuado contra sem pontuação) viraria divergência por formatação, não por conteúdo.
   */
  @Test
  void nomeEquivalenteAposNormalizacaoNaoDiverge() {
    LocalDate nascimento = LocalDate.of(1990, 5, 20);
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(
            new DocumentVerificationResult(
                checkAprovado(), new ExtractedDocumentFields("maria silva", "12345678900", nascimento)));
    when(subjectProfileService.find(SUBJECT, TENANT)).thenReturn(perfil(nascimento));
    when(subjectService.findById(SUBJECT, TENANT))
        .thenReturn(subject("MARIA SILVA", "123.456.789-00"));

    DocumentVerificationResult resultado =
        service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    assertThat(resultado.check().divergences()).isEmpty();
  }

  /**
   * Acento perdido no OCR não pode virar divergência — é o motivo de usar o
   * {@code NameNormalizer} do commons (NFD + remoção de marca diacrítica), não uma versão própria
   * que só remove pontuação.
   */
  @Test
  void nomeSemAcentoNoDocumentoNaoDiverge() {
    LocalDate nascimento = LocalDate.of(1990, 5, 20);
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(
            new DocumentVerificationResult(
                checkAprovado(), new ExtractedDocumentFields("JOAO", "12345678900", nascimento)));
    when(subjectProfileService.find(SUBJECT, TENANT)).thenReturn(perfil(nascimento));
    when(subjectService.findById(SUBJECT, TENANT)).thenReturn(subject("JOÃO", "12345678900"));

    DocumentVerificationResult resultado =
        service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    assertThat(resultado.check().divergences()).isEmpty();
  }

  /**
   * Número do documento (RG/CNH) divergindo do CPF do cadastro é esperado — são grandezas
   * diferentes (ver Javadoc de {@code ExtractedDocumentFields}) — e não pode virar fator de
   * risco. Reproduz o cenário real do stub (dev): CPF do subject × "00000000000" extraído.
   */
  @Test
  void numeroDoDocumentoDivergenteDoCpfNaoEComparadoNemPontua() {
    LocalDate nascimento = LocalDate.of(1990, 5, 20);
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(
            new DocumentVerificationResult(
                checkAprovado(),
                new ExtractedDocumentFields("MARIA SILVA", "00000000000", nascimento)));
    when(subjectProfileService.find(SUBJECT, TENANT)).thenReturn(perfil(nascimento));
    when(subjectService.findById(SUBJECT, TENANT))
        .thenReturn(subject("MARIA SILVA", "52998224725"));

    DocumentVerificationResult resultado =
        service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    assertThat(resultado.check().divergences()).isEmpty();
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
            Set.of(),
            Instant.now(),
            null);
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(new DocumentVerificationResult(falhou, null));

    DocumentVerificationResult resultado =
        service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    assertThat(resultado.extracted()).isNull();
    verifyNoInteractions(subjectProfileService, subjectService, fieldVerificationService);
    verify(repository).save(any(AssuranceCheck.class));
  }

  /**
   * Guard extra: mesmo que um provedor real venha a devolver {@code extracted} não-nulo junto de
   * um desfecho que não seja PASS, a reconciliação não deve rodar — documento reprovado não
   * sustenta comparação nenhuma, e rodar mesmo assim somaria failScore + divergenceScore pelo
   * mesmo evento.
   */
  @Test
  void extractedComDesfechoNaoPassNaoDisparaReconciliacao() {
    AssuranceCheck inconclusivo =
        new AssuranceCheck(
            UUID.randomUUID(),
            SUBJECT,
            TENANT,
            AssuranceKind.DOCUMENT,
            AssuranceOutcome.INCONCLUSIVE,
            30,
            "provedor",
            "doc-ref-3",
            "modelo/1.0",
            "hash",
            "foto tremida",
            Set.of(),
            Instant.now(),
            null);
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(
            new DocumentVerificationResult(
                inconclusivo, new ExtractedDocumentFields("MARIA SILVA", "12345678900", null)));

    service.verifyDocument(SUBJECT, TENANT, submissao(), consentimento());

    verifyNoInteractions(subjectProfileService, subjectService, fieldVerificationService);
  }
}
