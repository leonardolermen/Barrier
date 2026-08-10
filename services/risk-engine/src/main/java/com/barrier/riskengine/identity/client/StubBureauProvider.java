package com.barrier.riskengine.identity.client;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Provider stub de <b>CPF</b>: confirma qualquer CPF (já validado sintaticamente no domínio).
 * Não há bureau público/legal de CPF (sigilo fiscal + LGPD); a integração real é via Serpro
 * pago (ver {@link SerproBureauProvider}). CNPJ é atendido pelo {@link BrasilApiBureauProvider}.
 */
@Component
@Order(100) // stub tem baixa prioridade: só é usado quando não há bureau real para o tipo
public class StubBureauProvider implements BureauProvider {

  @Override
  public boolean supports(String documentType) {
    return "CPF".equals(documentType);
  }

  @Override
  public BureauResult check(BureauQuery query) {
    return BureauResult.match("stub: CPF confirmado");
  }

  @Override
  public String name() {
    return "stub";
  }

  /**
   * Não é verificação de identidade: confirma qualquer CPF. Marcar isto no próprio provider é o
   * que impede a cadeia de {@link com.barrier.riskengine.identity.service.IdentityService} de usá-lo
   * como fallback de um bureau real indisponível.
   */
  @Override
  public boolean authoritative() {
    return false;
  }
}
