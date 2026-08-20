package com.barrier.riskengine.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.tenant.service.ApiKeyService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Cobertura do filtro de autenticação de tenant por grupo de rota.
 *
 * <p>Existe por causa de uma falha concreta: {@code PROTECTED_PATHS} era uma allowlist
 * ({@code ^/v1/(assessments|subjects)(/.*)?$}) e os módulos {@code mesa} e {@code behavior}
 * nasceram fora dela. O efeito não foi só ausência de autenticação — como o
 * {@link TenantArgumentResolver} falha alto sem tenant, os dois módulos ficaram <b>inacessíveis</b>
 * para qualquer chamador. Duas entregas inteiras não funcionavam e nenhum teste apontava.
 *
 * <p>Por isso a allowlist virou <b>denylist</b>: tudo sob {@code /v1/} exige tenant, e as exceções
 * (administração) são as listadas. Endpoint novo agora nasce protegido — ver
 * {@link ApiRouteCoverageTest}, que é o teste que faltava.
 */
class TenantAuthenticationFilterTest {

  @Test
  void exigeCredencialNaMesaDeAnalise() throws Exception {
    assertThat(call("/v1/mesa/queues/ANALISE_PADRAO").getStatus())
        .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(call("/v1/mesa/cases/" + java.util.UUID.randomUUID()).getStatus())
        .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  void exigeCredencialNaIngestaoComportamental() throws Exception {
    assertThat(call("/v1/behavior-events").getStatus())
        .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  void exigeCredencialNosEndpointsQueJaEstavamCobertos() throws Exception {
    assertThat(call("/v1/assessments").getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(call("/v1/subjects/11144477735").getStatus())
        .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(call("/v1/subjects/11144477735/risk-state").getStatus())
        .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }

  /**
   * Rota de negócio ainda não escrita: o ponto do teste é que ela já está protegida hoje, sem
   * ninguém precisar lembrar de registrá-la em lugar nenhum.
   */
  @Test
  void protegeRotaDeNegocioQueAindaNaoExiste() throws Exception {
    assertThat(call("/v1/qualquer-coisa-nova").getStatus())
        .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }

  /**
   * Administração responde a outra pergunta ("é o operador do Barrier?") e é coberta pelo
   * {@link AdminApiKeyFilter}. Exigir tenant aqui quebraria o admin operando sobre outros tenants.
   */
  @Test
  void naoExigeTenantEmEndpointAdministrativo() throws Exception {
    assertThat(call("/v1/risk-rules").getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(call("/v1/risk-rules/SANCTION").getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(call("/v1/tenants/acme/risk-config").getStatus())
        .isEqualTo(HttpServletResponse.SC_OK);
    assertThat(call("/v1/tenants/acme/api-keys").getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }

  @Test
  void naoInterfereForaDaApiDeNegocio() throws Exception {
    assertThat(call("/actuator/health").getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }

  @Test
  void liberaComCredencialValida() throws Exception {
    ApiKeyService service = mock(ApiKeyService.class);
    when(service.authenticate(anyString()))
        .thenReturn(
            Optional.of(
                new com.barrier.riskengine.tenant.domain.AuthenticatedTenant(
                    new com.barrier.riskengine.tenant.domain.Tenant("acme", "Acme", true),
                    "chave-de-teste")));

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/behavior-events");
    request.addHeader("Authorization", "Bearer brr_chave_valida");
    MockHttpServletResponse response = new MockHttpServletResponse();
    new TenantAuthenticationFilter(service).doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(request.getAttribute(TenantAuthenticationFilter.TENANT_ATTRIBUTE)).isNotNull();
  }

  /** Sem credencial apresentada: nega sem chegar ao service. */
  private MockHttpServletResponse call(String uri) throws Exception {
    ApiKeyService service = mock(ApiKeyService.class);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    MockHttpServletResponse response = new MockHttpServletResponse();
    new TenantAuthenticationFilter(service).doFilter(request, response, new MockFilterChain());
    return response;
  }
}
