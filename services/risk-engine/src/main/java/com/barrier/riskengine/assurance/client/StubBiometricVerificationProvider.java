package com.barrier.riskengine.assurance.client;

import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Biometria facial + prova de vida simuladas para desenvolvimento.
 *
 * <p>Não aprova tudo, de propósito — foi assim que o stub de bureau escondeu por meses que ninguém
 * era verificado. A referência de captura escolhe o desfecho, então os caminhos de fraude e de
 * foto ruim continuam exercitáveis sem provedor contratado:
 *
 * <ul>
 *   <li>prefixo {@code fail-} → face não confere ou prova de vida falhou;
 *   <li>prefixo {@code inconclusive-} → qualidade insuficiente;
 *   <li>prefixo {@code unavailable-} → provedor fora do ar;
 *   <li>qualquer outra → autêntico.
 * </ul>
 */
@Component
@Profile("!prod")
public class StubBiometricVerificationProvider implements BiometricVerificationProvider {

  private final Clock clock;

  public StubBiometricVerificationProvider(Clock clock) {
    this.clock = clock;
  }

  @Override
  public AssuranceCheck verify(UUID subjectId, String tenantId, BiometricSubmission submission) {
    String reference = submission.selfieReference() == null ? "" : submission.selfieReference();
    Instant now = clock.instant();
    AssuranceOutcome outcome =
        reference.startsWith("fail-")
            ? AssuranceOutcome.FAIL
            : reference.startsWith("inconclusive-")
                ? AssuranceOutcome.INCONCLUSIVE
                : reference.startsWith("unavailable-")
                    ? AssuranceOutcome.UNAVAILABLE
                    : AssuranceOutcome.PASS;
    return new AssuranceCheck(
        UUID.randomUUID(),
        subjectId,
        tenantId,
        AssuranceKind.BIOMETRIC,
        outcome,
        outcome == AssuranceOutcome.PASS ? 97 : 20,
        name(),
        "stub:" + reference,
        "stub/1.0.0",
        submission.submittedHash(),
        "biometria simulada (prova de vida inclusa)",
        now);
  }

  @Override
  public String name() {
    return "biometria-simulada";
  }
}
