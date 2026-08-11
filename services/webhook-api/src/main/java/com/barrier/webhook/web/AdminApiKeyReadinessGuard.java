package com.barrier.webhook.web;

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
 * {@link AdminApiKeyFilter} fica inerte sem {@code barrier.admin.api-key} — conveniente em dev,
 * catastrófico em produção: o registro de endpoints define para onde vai o veredito de KYC de cada
 * parceiro, e aberto ele permite desviar os callbacks de qualquer tenant.
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
    List<String> problems = problems();
    if (problems.isEmpty()) {
      return;
    }
    if (Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
      throw new IllegalStateException(
          "Profile 'prod' ativo e barrier.admin.api-key inadequada ("
              + String.join("; ", problems)
              + "). /v1/webhook-endpoints define para onde vai o resultado de KYC de cada tenant e "
              + "não pode ficar sem autenticação. Defina ADMIN_API_KEY com um segredo aleatório de "
              + "pelo menos "
              + MIN_LENGTH
              + " caracteres.");
    }
    log.warn(
        "Registro de endpoints SEM autenticação ({}). Aceitável só fora de produção.",
        String.join("; ", problems));
  }

  private List<String> problems() {
    if (apiKey == null || apiKey.isBlank()) {
      return List.of("chave não configurada");
    }
    if (apiKey.length() < MIN_LENGTH) {
      return List.of("chave com menos de " + MIN_LENGTH + " caracteres");
    }
    return List.of();
  }
}
