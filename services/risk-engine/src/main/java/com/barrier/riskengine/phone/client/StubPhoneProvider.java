package com.barrier.riskengine.phone.client;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider de telefone para dev/testes: casa os dígitos do número contra uma lista configurável
 * de números/prefixos VoIP ({@code barrier.phone.voip-numbers}, CSV, vazia por padrão — sem
 * falso positivo em dev). Detecção real de VoIP depende de operadora/carrier lookup (provider
 * pago); substituir por uma implementação real atrás da mesma interface.
 */
@Component
public class StubPhoneProvider implements PhoneProvider {

  private final Set<String> voipNumbers;

  public StubPhoneProvider(@Value("${barrier.phone.voip-numbers:}") Set<String> voipNumbers) {
    this.voipNumbers = voipNumbers;
  }

  @Override
  public PhoneLookup lookup(String phone) {
    if (phone == null) {
      return PhoneLookup.UNKNOWN;
    }
    String digits = phone.replaceAll("\\D", "");
    boolean voip = voipNumbers.stream().anyMatch(digits::startsWith);
    return new PhoneLookup(voip);
  }
}
