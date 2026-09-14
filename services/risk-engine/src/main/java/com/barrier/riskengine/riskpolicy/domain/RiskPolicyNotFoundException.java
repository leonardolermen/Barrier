package com.barrier.riskengine.riskpolicy.domain;

import java.util.UUID;

/**
 * Lançada quando uma versão de {@link RiskPolicy} não é encontrada pelo {@code id}.
 *
 * <p>Substitui {@code NoSuchElementException} cru em {@code RiskPolicyRepositoryImpl.activate}/
 * {@code .archive} -- mesma razão de {@code PolicyStateException} não ser {@code
 * IllegalStateException}: o tipo é o que separa um conflito de domínio, que a camada web pode
 * mapear (404, quando o controller existir), de um erro de programação genérico da plataforma.
 */
public class RiskPolicyNotFoundException extends RuntimeException {

  public RiskPolicyNotFoundException(UUID id) {
    super("Política de risco não encontrada: " + id);
  }
}
