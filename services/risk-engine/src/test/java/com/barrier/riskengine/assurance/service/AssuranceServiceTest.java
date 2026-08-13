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
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AssuranceServiceTest {

  private static final UUID SUBJECT = UUID.randomUUID();

  private final DocumentVerificationProvider documentProvider =
      Mockito.mock(DocumentVerificationProvider.class);
  private final BiometricVerificationProvider biometricProvider =
      Mockito.mock(BiometricVerificationProvider.class);
  private final AssuranceCheckRepository repository = Mockito.mock(AssuranceCheckRepository.class);
  private final AssuranceRecordedListener listener = Mockito.mock(AssuranceRecordedListener.class);
  private final AssuranceService service =
      new AssuranceService(documentProvider, biometricProvider, repository, List.of(listener));

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
}
