package com.barrier.riskengine.assurance.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Providers de produção sem contrato real. Nunca aprovam, nunca reprovam — sempre
 * {@code UNAVAILABLE}, o mesmo tratamento que bureau indisponível já recebe (ver Javadoc das
 * classes).
 */
class UnavailableVerificationProvidersTest {

  private static final UUID SUBJECT = UUID.randomUUID();
  private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

  @Test
  void documentoscopiaDevolveSempreIndisponivel() {
    UnavailableDocumentVerificationProvider provider =
        new UnavailableDocumentVerificationProvider(clock);

    DocumentVerificationResult result =
        provider.verify(SUBJECT, "tenant-1", new DocumentSubmission("ref", "RG", "hash"));

    assertThat(result.check().outcome()).isEqualTo(AssuranceOutcome.UNAVAILABLE);
    assertThat(result.extracted()).isNull();
    assertThat(provider.name()).endsWith("-indisponivel");
  }

  @Test
  void biometriaDevolveSempreIndisponivel() {
    UnavailableBiometricVerificationProvider provider =
        new UnavailableBiometricVerificationProvider(clock);

    var check = provider.verify(SUBJECT, "tenant-1", new BiometricSubmission("s", "f", "hash"));

    assertThat(check.outcome()).isEqualTo(AssuranceOutcome.UNAVAILABLE);
    assertThat(provider.name()).endsWith("-indisponivel");
  }
}
