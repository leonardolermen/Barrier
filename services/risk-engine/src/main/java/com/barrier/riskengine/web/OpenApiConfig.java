package com.barrier.riskengine.web;

import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
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

  static {
    // AuthenticatedTenant e injetado pelo TenantArgumentResolver a partir da credencial: o parceiro
    // NUNCA o envia. Sem isto o springdoc o introspecta como argumento de controller e publica
    // "tenant" como query parameter obrigatorio, junto do formato interno de Tenant. Contrato que
    // descreve parametro inexistente e pior que contrato nenhum — o dev externo tenta, falha, e o
    // unico caminho de volta e falar com o time.
    SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthenticatedTenant.class);
  }

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
                        + "assinado com HMAC."))
        // Como autenticar E parte do contrato: sem o esquema declarado, o dev externo bate na
        // primeira parede com 401 e sem explicacao.
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description(
                            // Sem citar a rota que a emite: ela e administrativa e nao pertence
                            // ao contrato publicado.
                            "Credencial do tenant, fornecida pela operacao do Barrier."
                                + " Formato: Authorization: Bearer brr_<keyId>_<secret>.")))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
  }
}
