package com.barrier.webhook.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadados do contrato da Webhook API.
 *
 * <p>Sem grupos, ao contrario da risk-engine: <b>toda</b> rota deste servico e administrativa
 * (registrar destino, rotacionar segredo), entao nao ha subconjunto publicavel a separar. Se um dia
 * surgir endpoint que o parceiro chame — historico de entrega, reenvio —, os grupos entram aqui no
 * mesmo desenho.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI metadados() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Barrier — Webhook API (administrativa)")
                .version("v1")
                .description(
                    "Registro do destino de callback por tenant e rotacao do segredo HMAC. "
                        + "Protegida por X-Admin-Key; nao e superficie de parceiro."));
  }
}
