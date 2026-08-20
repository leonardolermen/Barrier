package com.barrier.riskengine.assurance.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assurance.client.BiometricSubmission;
import com.barrier.riskengine.assurance.client.DocumentSubmission;
import com.barrier.riskengine.assurance.client.DocumentVerificationResult;
import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceDisabledException;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.domain.DocumentGateNotSatisfiedException;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import com.barrier.riskengine.subject.profile.service.FieldVerificationService;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import com.barrier.riskengine.subject.service.SubjectService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.annotation.Transactional;

class AssuranceServiceTest {

  private static final UUID SUBJECT = UUID.randomUUID();

  private final DocumentVerificationProvider documentProvider =
      Mockito.mock(DocumentVerificationProvider.class);
  private final BiometricVerificationProvider biometricProvider =
      Mockito.mock(BiometricVerificationProvider.class);
  private final AssuranceCheckRepository repository = Mockito.mock(AssuranceCheckRepository.class);
  private final AssuranceRecordedListener listener = Mockito.mock(AssuranceRecordedListener.class);
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
          List.of(listener),
          subjectProfileService,
          subjectService,
          fieldVerificationService,
          true,
          0.85,
          java.time.Duration.ofHours(24));

  private DocumentSubmission submissao() {
    return new DocumentSubmission("ref", "RG", "hash");
  }

  private AssuranceCheck checkSemConsentimento() {
    return new AssuranceCheck(
        UUID.randomUUID(),
        SUBJECT,
        "tenant-1",
        AssuranceKind.DOCUMENT,
        AssuranceOutcome.PASS,
        90,
        "stub",
        "abc-123",
        "v1",
        "hash",
        "ok",
        Set.of(),
        Instant.now(),
        null);
  }

  private AssuranceConsent consentimento() {
    return new AssuranceConsent("ref-1", "EKYC", Instant.now());
  }

  @Test
  void notifica_listener_em_qualquer_desfecho() {
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(new DocumentVerificationResult(checkSemConsentimento(), null));

    service.verifyDocument(SUBJECT, "tenant-1", submissao(), consentimento());

    verify(listener).onRecorded(any(AssuranceCheck.class));
  }

  @Test
  void falha_do_listener_nao_desfaz_a_verificacao() {
    when(documentProvider.verify(any(), any(), any()))
        .thenReturn(new DocumentVerificationResult(checkSemConsentimento(), null));
    doThrow(new RuntimeException("banco fora")).when(listener).onRecorded(any());

    assertThatCode(() -> service.verifyDocument(SUBJECT, "tenant-1", submissao(), consentimento()))
        .doesNotThrowAnyException();
    verify(repository).save(any(AssuranceCheck.class));
  }

  /**
   * Consentimento nulo tem de recusar cedo com mensagem clara, como o resto do domínio — não
   * estourar {@code NullPointerException} no meio de {@code consent.validate()}. O provider nunca
   * pode ser acionado nesse caminho: chamá-lo antes de saber que o consentimento é inválido
   * gastaria uma consulta (possivelmente paga) à toa.
   */
  /**
   * Kill switch: desligado, o serviço recusa antes de acionar qualquer provedor — nenhum
   * {@code AssuranceCheck} nasce, então nenhuma reavaliação é disparada.
   */
  @Test
  void recusaVerificacaoDeDocumentoQuandoDesabilitado() {
    AssuranceService desabilitado =
        new AssuranceService(
            documentProvider,
            biometricProvider,
            repository,
            List.of(listener),
            subjectProfileService,
            subjectService,
            fieldVerificationService,
            false,
            0.85,
            java.time.Duration.ofHours(24));

    assertThatThrownBy(
            () -> desabilitado.verifyDocument(SUBJECT, "tenant-1", submissao(), consentimento()))
        .isInstanceOf(AssuranceDisabledException.class)
        .hasMessageContaining("desabilitada");

    verifyNoInteractions(documentProvider, repository, listener);
  }

  @Test
  void recusaVerificacaoBiometricaQuandoDesabilitado() {
    AssuranceService desabilitado =
        new AssuranceService(
            documentProvider,
            biometricProvider,
            repository,
            List.of(listener),
            subjectProfileService,
            subjectService,
            fieldVerificationService,
            false,
            0.85,
            java.time.Duration.ofHours(24));

    assertThatThrownBy(
            () ->
                desabilitado.verifyBiometrics(
                    SUBJECT,
                    "tenant-1",
                    new BiometricSubmission("selfie", "face", "hash"),
                    consentimento()))
        .isInstanceOf(AssuranceDisabledException.class)
        .hasMessageContaining("desabilitada");

    verifyNoInteractions(biometricProvider, repository, listener);
  }

  @Test
  void recusaDocumentoSemConsentimento() {
    assertThatThrownBy(
            () ->
                service.verifyDocument(
                    UUID.randomUUID(), "t1", new DocumentSubmission("ref", "RG", "hash"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("consentimento");

    verifyNoInteractions(documentProvider, repository);
  }

  @Test
  void recusaBiometriaSemConsentimento() {
    assertThatThrownBy(
            () ->
                service.verifyBiometrics(
                    UUID.randomUUID(), "t1", new BiometricSubmission("selfie", "face", "hash"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("consentimento");

    verifyNoInteractions(biometricProvider, repository);
  }

  /**
   * {@code scheduleNotification} só notifica na hora (fora de {@code afterCommit}) quando não há
   * transação Spring ativa — e isso só é seguro porque os dois métodos públicos que chamam
   * {@code persist} são {@code @Transactional}. Se um método público novo chamar {@code persist}
   * sem a anotação, o fallback deixaria de ser "só em teste unitário" e passaria a valer também
   * em produção, sem a proteção pós-commit que o CRITICAL-1 corrigiu — silenciosamente, porque
   * nada quebra na hora. Este teste transforma esse invariante em regressão de build: todo
   * método público de {@code AssuranceService} tem de ser {@code @Transactional}.
   */
  @Test
  void todoMetodoPublicoETransactional() {
    for (Method method : AssuranceService.class.getDeclaredMethods()) {
      if (!Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      org.assertj.core.api.Assertions.assertThat(method.isAnnotationPresent(Transactional.class))
          .as(
              "AssuranceService.%s é público e não é @Transactional — o fallback de"
                  + " scheduleNotification depende de todo método público ser transacional para o"
                  + " pitfall do afterCommit não valer em produção",
              method.getName())
          .isTrue();
    }
  }

  // --- attempts: janela + contagem no banco (itens 2 e 3 do plano de throttle) ------------------

  /**
   * {@code attempts} tem de delegar a contagem para o repositório, passando a janela configurada
   * — não materializar {@code findAll} e contar em memória. Este teste ficaria vermelho tanto se
   * {@code attempts} voltasse a usar {@code findAll}/stream quanto se a janela passada não fosse
   * a configurada no construtor.
   */
  @Test
  void attemptsDelegaParaCountRecentComAJanelaConfigurada() {
    java.time.Duration janela = java.time.Duration.ofHours(24);
    AssuranceService servico =
        new AssuranceService(
            documentProvider,
            biometricProvider,
            repository,
            List.of(listener),
            subjectProfileService,
            subjectService,
            fieldVerificationService,
            true,
            0.85,
            janela);
    Mockito.when(repository.countRecent(SUBJECT, "tenant-1", AssuranceKind.BIOMETRIC, janela))
        .thenReturn(3L);

    long total = servico.attempts(SUBJECT, "tenant-1", AssuranceKind.BIOMETRIC);

    org.assertj.core.api.Assertions.assertThat(total).isEqualTo(3L);
    verify(repository).countRecent(SUBJECT, "tenant-1", AssuranceKind.BIOMETRIC, janela);
    verify(repository, Mockito.never()).findAll(Mockito.any(), Mockito.any());
  }

  // --- documentoscopia como pré-requisito da biometria (decisão de produto 2026-08-13) ----------

  private AssuranceCheck checkComOutcome(AssuranceKind kind, AssuranceOutcome outcome) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        SUBJECT,
        "tenant-1",
        kind,
        outcome,
        90,
        "stub",
        "abc-123",
        "v1",
        "hash",
        "ok",
        Set.of(),
        Instant.now(),
        null);
  }

  private BiometricSubmission biometricSubmission() {
    return new BiometricSubmission("selfie", "face", "hash");
  }

  /**
   * Sem nenhum {@code AssuranceCheck} de DOCUMENT para o par, a biometria recusa antes de acionar
   * o provider — que é chamada paga. {@code DocumentGateNotSatisfiedException}, não {@code
   * AssuranceDisabledException}: o parceiro precisa distinguir "falta documentoscopia" de "kill switch
   * desligado".
   */
  @Test
  void recusaBiometriaSemDocumentoscopiaAlguma() {
    when(repository.findLatest(SUBJECT, "tenant-1", AssuranceKind.DOCUMENT))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.verifyBiometrics(
                    SUBJECT, "tenant-1", biometricSubmission(), consentimento()))
        .isInstanceOf(DocumentGateNotSatisfiedException.class);

    verifyNoInteractions(biometricProvider);
    verify(repository, Mockito.never()).save(Mockito.any());
  }

  /**
   * Comparar rosto contra um documento que não passou na autenticidade prova pouco — só {@code
   * PASS} libera. FAIL/INCONCLUSIVE/UNAVAILABLE recusam do mesmo jeito que ausência total.
   */
  @Test
  void recusaBiometriaQuandoDocumentoscopiaNaoPassou() {
    for (AssuranceOutcome outcome :
        Set.of(AssuranceOutcome.FAIL, AssuranceOutcome.INCONCLUSIVE, AssuranceOutcome.UNAVAILABLE)) {
      when(repository.findLatest(SUBJECT, "tenant-1", AssuranceKind.DOCUMENT))
          .thenReturn(Optional.of(checkComOutcome(AssuranceKind.DOCUMENT, outcome)));

      assertThatThrownBy(
              () ->
                  service.verifyBiometrics(
                      SUBJECT, "tenant-1", biometricSubmission(), consentimento()))
          .as("outcome %s não pode liberar a biometria", outcome)
          .isInstanceOf(DocumentGateNotSatisfiedException.class);
    }

    verifyNoInteractions(biometricProvider);
    verify(repository, Mockito.never()).save(Mockito.any());
  }

  /** Documentoscopia PASS libera a biometria normalmente, acionando o provider. */
  @Test
  void processaBiometriaQuandoDocumentoscopiaPassou() {
    when(repository.findLatest(SUBJECT, "tenant-1", AssuranceKind.DOCUMENT))
        .thenReturn(Optional.of(checkComOutcome(AssuranceKind.DOCUMENT, AssuranceOutcome.PASS)));
    when(subjectService.findById(SUBJECT, "tenant-1"))
        .thenReturn(
            new com.barrier.riskengine.subject.domain.Subject(
                SUBJECT, "CPF", "12345678900", "Fulano de Tal", Instant.now()));
    when(biometricProvider.requestVerification(any(), any(), any(), any()))
        .thenReturn(checkComOutcome(AssuranceKind.BIOMETRIC, AssuranceOutcome.PASS));

    AssuranceCheck result =
        service.verifyBiometrics(SUBJECT, "tenant-1", biometricSubmission(), consentimento());

    org.assertj.core.api.Assertions.assertThat(result.kind()).isEqualTo(AssuranceKind.BIOMETRIC);
    verify(biometricProvider).requestVerification(any(), any(), any(), any());
    verify(repository).save(Mockito.any());
  }
}
