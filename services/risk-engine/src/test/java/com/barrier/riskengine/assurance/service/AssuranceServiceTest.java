package com.barrier.riskengine.assurance.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.barrier.riskengine.assurance.client.BiometricSubmission;
import com.barrier.riskengine.assurance.client.DocumentSubmission;
import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AssuranceServiceTest {

  private final DocumentVerificationProvider documentProvider =
      Mockito.mock(DocumentVerificationProvider.class);
  private final BiometricVerificationProvider biometricProvider =
      Mockito.mock(BiometricVerificationProvider.class);
  private final AssuranceCheckRepository repository = Mockito.mock(AssuranceCheckRepository.class);
  private final AssuranceService service =
      new AssuranceService(documentProvider, biometricProvider, repository);

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
