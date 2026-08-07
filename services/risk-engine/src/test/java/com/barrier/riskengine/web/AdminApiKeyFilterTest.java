package com.barrier.riskengine.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminApiKeyFilterTest {

  private static final String KEY = "chave-de-admin-com-tamanho-suficiente-123";

  @Test
  void bloqueiaEndpointAdministrativoSemChave() throws Exception {
    MockHttpServletResponse response = call(new AdminApiKeyFilter(KEY), "/v1/risk-rules", null);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  void bloqueiaEndpointAdministrativoComChaveErrada() throws Exception {
    MockHttpServletResponse response = call(new AdminApiKeyFilter(KEY), "/v1/risk-rules", "errada");

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  void liberaEndpointAdministrativoComChaveCorreta() throws Exception {
    MockHttpServletResponse response = call(new AdminApiKeyFilter(KEY), "/v1/risk-rules", KEY);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }

  /** O kill switch global e o override por parceiro são os dois alvos que precisam da trava. */
  @Test
  void protegeTambemORiskConfigPorTenantEOsSubcaminhosDoRegistry() throws Exception {
    assertThat(call(new AdminApiKeyFilter(KEY), "/v1/risk-rules/SANCTION", null).getStatus())
        .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(
            call(new AdminApiKeyFilter(KEY), "/v1/tenants/acme/risk-config", null).getStatus())
        .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }

  /** Endpoints de negócio seguem no fluxo normal (autorização por tenant, não por admin). */
  @Test
  void naoInterfereEmEndpointsDeNegocio() throws Exception {
    assertThat(call(new AdminApiKeyFilter(KEY), "/v1/assessments", null).getStatus())
        .isEqualTo(HttpServletResponse.SC_OK);
    assertThat(call(new AdminApiKeyFilter(KEY), "/v1/subjects/11144477735", null).getStatus())
        .isEqualTo(HttpServletResponse.SC_OK);
  }

  /** Sem chave configurada o filtro fica inerte — dev/testes; em prod o guard barra a subida. */
  @Test
  void semChaveConfiguradaNaoBloqueia() throws Exception {
    assertThat(call(new AdminApiKeyFilter(""), "/v1/risk-rules", null).getStatus())
        .isEqualTo(HttpServletResponse.SC_OK);
  }

  private MockHttpServletResponse call(AdminApiKeyFilter filter, String uri, String header)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", uri);
    request.setRequestURI(uri);
    if (header != null) {
      request.addHeader(AdminApiKeyFilter.HEADER, header);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }
}
