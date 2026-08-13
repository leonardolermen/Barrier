package com.barrier.riskengine.subject.profile.client.interfaces;

import com.barrier.riskengine.subject.profile.domain.RegistryValidationRequest;
import com.barrier.riskengine.subject.profile.domain.RegistryValidationResult;
import java.util.Optional;
import java.util.UUID;

/**
 * Integração externa só por interface (regra inegociável do módulo, ver CLAUDE.md) — validação
 * cadastral contra RFB/SENATRAN (Datavalid/Serpro, {@code pessoa-fisica/validacao}).
 *
 * <p>{@link Optional#empty()} significa indisponibilidade do provedor (disjuntor aberto, erro de
 * transporte, 5xx) — nunca ausência de dado: quando a consulta funciona mas não encontra o CPF na
 * RFB ({@code rfb_existe = false}), o {@link com.barrier.riskengine.subject.profile.domain.RegistryValidationResult}
 * ainda volta preenchido, só com {@code rfbExiste = false}.
 */
public interface RegistryValidationProvider {

  Optional<RegistryValidationResult> validate(
      UUID subjectId, String tenantId, RegistryValidationRequest request);

  String name();
}
