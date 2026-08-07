package com.barrier.riskengine.web;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Trava de segurança operacional, no mesmo espírito do {@code WatchlistReadinessGuard}:
 * {@link AdminApiKeyFilter} fica inerte quando {@code barrier.admin.api-key} não está definida —
 * conveniente em dev, catastrófico em produção, porque deixaria {@code /v1/risk-rules} aberto a
 * quem alcançar a porta.
 *
 * <p>Falha rápido (não sobe) se o profile {@code prod} estiver ativo sem a chave configurada, ou
 * com uma chave curta demais para resistir a tentativa e erro. Em outros profiles, apenas avisa.
 */
@Component
public class AdminApiKeyReadinessGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AdminApiKeyReadinessGuard.class);
  private static final String PROD_PROFILE = "prod";
  private static final int MIN_LENGTH = 32;

  private final String apiKey;
  private final Environment environment;

  public AdminApiKeyReadinessGuard(
      @Value("${barrier.admin.api-key:}") String apiKey, Environment environment) {
    this.apiKey = apiKey;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean prod = Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
    List<String> problems = problems();
    if (problems.isEmpty()) {
      return;
    }
    if (prod) {
      throw new IllegalStateException(
          "Profile 'prod' ativo e barrier.admin.api-key inadequada ("
              + String.join("; ", problems)
              + "). Os endpoints administrativos (/v1/risk-rules, /v1/tenants/*/risk-config) mudam "
              + "como o motor decide para todos os tenants e não podem ficar sem autenticação. "
              + "Defina ADMIN_API_KEY com um segredo aleatório de pelo menos "
              + MIN_LENGTH
              + " caracteres.");
    }
    log.warn(
        "Endpoints administrativos SEM autenticação ({}). Aceitável só fora de produção.",
        String.join("; ", problems));
  }

  private List<String> problems() {
    if (apiKey.isBlank()) {
      return List.of("chave não configurada");
    }
    if (apiKey.length() < MIN_LENGTH) {
      return List.of("chave com menos de " + MIN_LENGTH + " caracteres");
    }
    return List.of();
  }
}
