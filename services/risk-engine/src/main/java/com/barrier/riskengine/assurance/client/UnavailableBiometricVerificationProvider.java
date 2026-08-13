package com.barrier.riskengine.assurance.client;

import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Provedor de biometria para produção sem contrato real firmado. Simétrico de
 * {@link UnavailableDocumentVerificationProvider} — mesmo motivo de existir (o construtor
 * obrigatório de {@code AssuranceService} precisa de um bean de {@code
 * BiometricVerificationProvider} em qualquer profile, inclusive quando o parceiro ainda não usa
 * biometria), mesmo desfecho ({@code UNAVAILABLE}, nunca {@code PASS}/{@code FAIL}) e mesmo
 * motivo para {@code @ConditionalOnMissingBean} (ver Javadoc lá): sem ela, contratar um
 * provedor real de biometria derrubaria o contexto em {@code prod} com
 * {@code NoUniqueBeanDefinitionException} no dia em que o bean real fosse registrado.
 */
@Component
@Profile("prod")
@ConditionalOnMissingBean(BiometricVerificationProvider.class)
public class UnavailableBiometricVerificationProvider implements BiometricVerificationProvider {

  private final Clock clock;

  public UnavailableBiometricVerificationProvider(Clock clock) {
    this.clock = clock;
  }

  @Override
  public AssuranceCheck verify(UUID subjectId, String tenantId, BiometricSubmission submission) {
    Instant now = clock.instant();
    return new AssuranceCheck(
        UUID.randomUUID(),
        subjectId,
        tenantId,
        AssuranceKind.BIOMETRIC,
        AssuranceOutcome.UNAVAILABLE,
        null,
        name(),
        null,
        null,
        submission.submittedHash(),
        "nenhum provedor real de biometria contratado",
        Set.of(),
        now,
        null);
  }

  @Override
  public String name() {
    return "biometria-indisponivel";
  }
}
