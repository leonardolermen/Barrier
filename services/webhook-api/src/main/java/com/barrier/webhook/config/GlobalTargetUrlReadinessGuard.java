package com.barrier.webhook.config;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Proíbe o destino global de webhook em produção.
 *
 * <p>{@code barrier.webhook.target-url} é <b>um</b> endereço para todos os tenants. Ele sobrevive
 * como conveniência de desenvolvimento (um destino, nenhum dado real), mas em produção é vazamento
 * cross-tenant por desenho: basta um tenant sem endpoint registrado para que o veredito de PLD-FT
 * dos clientes dele — com documento e nome — chegue no endpoint de outra empresa. Entregar no lugar
 * errado é irreversível, então a checagem é de startup e não de runtime.
 *
 * <p>Em prod, cada tenant tem que estar em {@code webhook_endpoints}; sem registro, a entrega
 * simplesmente não acontece e fica logada — a decisão continua disponível na risk-engine.
 */
@Component
public class GlobalTargetUrlReadinessGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(GlobalTargetUrlReadinessGuard.class);
  private static final String PROD_PROFILE = "prod";

  private final WebhookProperties properties;
  private final Environment environment;

  public GlobalTargetUrlReadinessGuard(WebhookProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    String global = properties.targetUrl();
    if (global == null || global.isBlank()) {
      return;
    }
    if (Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
      throw new IllegalStateException(
          "Profile 'prod' ativo com barrier.webhook.target-url definido. Esse destino é único para "
              + "todos os tenants: o callback de KYC de um cliente chegaria no endpoint de outro. "
              + "Remova WEBHOOK_TARGET_URL e registre o destino de cada tenant em "
              + "PUT /v1/webhook-endpoints/{tenantId}.");
    }
    log.warn(
        "Destino global de webhook configurado ({}): vale para qualquer tenant sem endpoint "
            + "registrado. Aceitável só fora de produção.",
        global);
  }
}
