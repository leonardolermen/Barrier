package com.barrier.riskengine.web;

import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import com.barrier.riskengine.tenant.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica o tenant pela credencial em {@code Authorization: Bearer brr_<keyId>_<secret>} e
 * publica o resultado como atributo da requisição, de onde o {@link TenantArgumentResolver} o
 * injeta nos controllers.
 *
 * <p>Substitui a confiança no header {@code X-Client-Id}, que era autodeclarado: qualquer chamador
 * que conhecesse (ou adivinhasse) um id de tenant lia avaliações alheias e, pior, podia
 * <b>aprovar</b> uma avaliação em revisão de outro cliente. O header agora é <b>ignorado</b> — não
 * é lido, não é validado, não influencia nada. Aceitá-lo como fallback recriaria o buraco.
 *
 * <p>Endpoints administrativos não passam por aqui: são protegidos pelo {@link AdminApiKeyFilter},
 * que responde a outra pergunta ("é o operador do Barrier?", não "é qual cliente?").
 */
@Component
@Order(3) // depois do correlation id e do gate de admin
public class TenantAuthenticationFilter extends OncePerRequestFilter {

  static final String TENANT_ATTRIBUTE = "barrier.authenticatedTenant";

  private static final Logger log = LoggerFactory.getLogger(TenantAuthenticationFilter.class);
  private static final String BEARER = "Bearer ";

  private final ApiKeyService apiKeyService;

  public TenantAuthenticationFilter(ApiKeyService apiKeyService) {
    this.apiKeyService = apiKeyService;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !ApiRoutes.isTenantScoped(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<AuthenticatedTenant> tenant = bearerToken(request).flatMap(apiKeyService::authenticate);
    if (tenant.isEmpty()) {
      log.warn("Acesso negado a {} {}", request.getMethod(), request.getRequestURI());
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response
          .getWriter()
          .write(
              "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                  + "\"detail\":\"Credencial ausente ou inválida. Use Authorization: Bearer <api-key>\"}");
      return;
    }
    request.setAttribute(TENANT_ATTRIBUTE, tenant.get());
    chain.doFilter(request, response);
  }

  private Optional<String> bearerToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER)) {
      return Optional.empty();
    }
    return Optional.of(header.substring(BEARER.length()));
  }
}
