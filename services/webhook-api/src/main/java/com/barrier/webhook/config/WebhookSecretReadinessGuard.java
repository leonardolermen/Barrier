package com.barrier.webhook.config;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Trava de segurança operacional do segredo HMAC que assina os callbacks.
 *
 * <p>{@code application.yml} traz {@code secret: ${WEBHOOK_SECRET:dev-secret}} — o default existe
 * para dev funcionar sem configuração, mas é uma string <b>pública</b>, versionada neste
 * repositório. Um deploy de produção que esqueça de injetar {@code WEBHOOK_SECRET} assinaria todos
 * os callbacks com ela, e qualquer um poderia forjar um resultado de KYC ("APROVADO") no endpoint
 * do cliente com assinatura válida. Sem esta trava, a falha é silenciosa: os webhooks continuam
 * sendo entregues normalmente.
 *
 * <p>Falha rápido (não sobe) se o profile {@code prod} estiver ativo e o segredo estiver ausente,
 * for o default de dev, ou for curto demais. Em outros profiles, apenas avisa.
 */
@Component
public class WebhookSecretReadinessGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(WebhookSecretReadinessGuard.class);
  private static final String PROD_PROFILE = "prod";
  private static final String DEV_DEFAULT = "dev-secret";
  private static final int MIN_LENGTH = 32;

  private final WebhookProperties properties;
  private final Environment environment;

  public WebhookSecretReadinessGuard(WebhookProperties properties, Environment environment) {
    this.properties = properties;
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
          "Profile 'prod' ativo com segredo HMAC inadequado ("
              + String.join("; ", problems)
              + "). Os callbacks seriam assinados com um segredo conhecido publicamente, "
              + "permitindo forjar resultados de KYC no endpoint do cliente. Defina WEBHOOK_SECRET "
              + "com um segredo aleatório de pelo menos "
              + MIN_LENGTH
              + " caracteres.");
    }
    log.warn(
        "Segredo HMAC do webhook inadequado ({}). Aceitável só fora de produção.",
        String.join("; ", problems));
  }

  private List<String> problems() {
    String secret = properties.secret();
    if (secret == null || secret.isBlank()) {
      return List.of("segredo não configurado");
    }
    if (DEV_DEFAULT.equals(secret)) {
      return List.of("usando o default de desenvolvimento '" + DEV_DEFAULT + "'");
    }
    if (secret.length() < MIN_LENGTH) {
      return List.of("segredo com menos de " + MIN_LENGTH + " caracteres");
    }
    return List.of();
  }
}
