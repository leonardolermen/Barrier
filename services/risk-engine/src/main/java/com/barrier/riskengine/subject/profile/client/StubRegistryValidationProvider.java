package com.barrier.riskengine.subject.profile.client;

import com.barrier.riskengine.subject.profile.client.interfaces.RegistryValidationProvider;
import com.barrier.riskengine.subject.profile.domain.RegistryValidationRequest;
import com.barrier.riskengine.subject.profile.domain.RegistryValidationResult;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/testes: mesma convenção do restante do assurance/identity — aprova tudo por padrão, exceto
 * quando o CPF de teste começa com {@code 999}, que devolve tudo divergente (bate falso), para
 * exercitar o caminho de divergência sem depender do provedor real.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(
    name = "barrier.registry-validation.enabled",
    havingValue = "false",
    matchIfMissing = true)
public class StubRegistryValidationProvider implements RegistryValidationProvider {

  @Override
  public Optional<RegistryValidationResult> validate(
      UUID subjectId, String tenantId, RegistryValidationRequest request) {
    boolean divergente = request.cpf() != null && request.cpf().startsWith("999");
    RegistryValidationResult.Rfb rfb =
        new RegistryValidationResult.Rfb(
            divergente ? 0.2 : 1.0, null, !divergente, !divergente, !divergente);
    return Optional.of(new RegistryValidationResult(true, false, rfb, null, "stub"));
  }

  @Override
  public String name() {
    return "stub-registry-validation";
  }
}
