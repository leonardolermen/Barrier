package com.barrier.riskengine.assurance.client;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * O CRITICAL fechado pela revisão final: este guard injetava os providers de assurance por
 * construtor obrigatório e, em produção, não havia bean nenhum ({@code Stub*} são
 * {@code @Profile("!prod")}) — {@code UnsatisfiedDependencyException} derrubava o contexto
 * inteiro. Este teste prova que construir/rodar o guard com os providers de produção
 * (indisponíveis, nunca simulados) não lança nada — é só aviso em log.
 */
class AssuranceProviderReadinessGuardTest {

  @Test
  void naoLancaComProvidersDeProducaoIndisponiveis() {
    DocumentVerificationProvider document =
        new UnavailableDocumentVerificationProvider(Clock.systemUTC());
    BiometricVerificationProvider biometric =
        new UnavailableBiometricVerificationProvider(Clock.systemUTC());
    AssuranceProviderReadinessGuard guard = new AssuranceProviderReadinessGuard(document, biometric);

    assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
  }

  /** Mesmo em tese: um provider real (nome sem sufixo conhecido) também não deve lançar. */
  @Test
  void naoLancaComProviderRealHipotetico() {
    DocumentVerificationProvider real =
        new DocumentVerificationProvider() {
          @Override
          public DocumentVerificationResult verify(
              UUID subjectId, String tenantId, DocumentSubmission submission) {
            return new DocumentVerificationResult(
                new AssuranceCheck(
                    UUID.randomUUID(),
                    subjectId,
                    tenantId,
                    AssuranceKind.DOCUMENT,
                    AssuranceOutcome.PASS,
                    95,
                    name(),
                    "ref",
                    "v1",
                    submission.submittedHash(),
                    "ok",
                    Set.of(),
                    Instant.now(),
                    null),
                null);
          }

          @Override
          public String name() {
            return "provedor-real-contratado";
          }
        };
    BiometricVerificationProvider biometric =
        new UnavailableBiometricVerificationProvider(Clock.systemUTC());

    assertThatCode(() -> new AssuranceProviderReadinessGuard(real, biometric).run(null))
        .doesNotThrowAnyException();
  }
}
