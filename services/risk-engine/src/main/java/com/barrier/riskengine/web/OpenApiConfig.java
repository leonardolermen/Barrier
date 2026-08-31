package com.barrier.riskengine.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dois contratos, e a separacao e de seguranca, nao de organizacao.
 *
 * <p>O spec default documenta TUDO — inclusive emitir credencial de tenant ({@code /v1/tenants/**})
 * e ligar ou desligar regra regulatoria ({@code /v1/risk-rules}). Publicar isso entrega o mapa da
 * superficie administrativa a quem nunca deveria saber que ela existe: mesmo raciocinio que tirou o
 * {@code /actuator} da porta de negocio.
 *
 * <p>O grupo {@code parceiro} e o artefato publicavel. O grupo {@code admin} existe para uso
 * interno e <b>nunca</b> e publicado — nao esta protegido por estar escondido (o
 * {@code AdminApiKeyFilter} e quem protege), mas nao ha razao de dar o mapa de graca.
 *
 * <p>A lista de caminhos administrativos e escrita aqui e conferida contra {@link ApiRoutes} pelo
 * teste de cobertura: duas copias de uma mesma verdade divergem, e a divergencia aqui e um endpoint
 * administrativo publicado sem ninguem notar.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  public GroupedOpenApi contratoDoParceiro() {
    return GroupedOpenApi.builder()
        .group("parceiro")
        .pathsToMatch("/v1/**")
        .pathsToExclude("/v1/risk-rules/**", "/v1/tenants/**", "/v1/webhook-endpoints/**")
        .build();
  }

  @Bean
  public GroupedOpenApi contratoAdministrativo() {
    return GroupedOpenApi.builder()
        .group("admin")
        .pathsToMatch("/v1/risk-rules/**", "/v1/tenants/**", "/v1/webhook-endpoints/**")
        .build();
  }

  @Bean
  public OpenAPI metadados() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Barrier — motor de decisao de KYC/PLD-FT")
                .version("v1")
                .description(
                    "Avaliacao de risco com fatores explicaveis e trilha auditavel. "
                        + "Toda rota exige credencial de tenant; o desfecho chega por webhook "
                        + "assinado com HMAC."));
  }
}
