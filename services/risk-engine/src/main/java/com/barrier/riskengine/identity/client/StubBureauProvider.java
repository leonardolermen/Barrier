package com.barrier.riskengine.identity.client;

import org.springframework.stereotype.Component;

/**
 * Provider stub para desenvolvimento: confirma qualquer documento (já validado sintaticamente
 * no domínio). Será substituído pelas integrações reais (ver {@link SerproBureauProvider}).
 */
@Component
public class StubBureauProvider implements BureauProvider {

  @Override
  public boolean supports(String documentType) {
    return "CPF".equals(documentType) || "CNPJ".equals(documentType);
  }

  @Override
  public BureauResult check(BureauQuery query) {
    return BureauResult.match("stub: documento confirmado");
  }

  @Override
  public String name() {
    return "stub";
  }
}
