package com.barrier.riskengine.email.client;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider de email: casa o domínio contra uma lista de domínios descartáveis conhecidos
 * (configurável em {@code barrier.email.disposable-domains}; o padrão cobre provedores públicos
 * de email temporário). Idade do domínio (WHOIS) fica para quando houver um provedor real —
 * não incluída aqui.
 */
@Component
public class StubEmailProvider implements EmailProvider {

  private final Set<String> disposableDomains;

  public StubEmailProvider(
      @Value(
              "${barrier.email.disposable-domains:mailinator.com,guerrillamail.com,10minutemail.com,"
                  + "tempmail.com,throwawaymail.com,yopmail.com,trashmail.com,getnada.com}")
          Set<String> disposableDomains) {
    this.disposableDomains = disposableDomains;
  }

  @Override
  public EmailLookup lookup(String email) {
    if (email == null || !email.contains("@")) {
      return EmailLookup.UNKNOWN;
    }
    String domain = email.substring(email.indexOf('@') + 1).trim().toLowerCase();
    return new EmailLookup(disposableDomains.contains(domain));
  }
}
