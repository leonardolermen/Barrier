package com.barrier.riskengine.credit.client;

import org.springframework.stereotype.Component;

/**
 * Sem integração real de score externo ainda (Serasa/Boa Vista/SCR) — sempre devolve
 * {@link CreditScoreLookup#UNKNOWN}, então {@code CreditScoreRiskRule} nunca pontua até um
 * provider real substituir este atrás da mesma interface.
 */
@Component
public class StubCreditScoreProvider implements CreditScoreProvider {

  @Override
  public CreditScoreLookup lookup(String documentType, String documentDigits) {
    return CreditScoreLookup.UNKNOWN;
  }
}
