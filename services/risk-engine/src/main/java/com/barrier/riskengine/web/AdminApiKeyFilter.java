package com.barrier.riskengine.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exige {@code X-Admin-Key} nos endpoints de <b>administração</b> — os que mudam como o motor
 * decide, para todos os tenants ou para um parceiro específico.
 *
 * <p>Motivação: {@code /v1/risk-rules} não tinha autenticação nem escopo de tenant, então um
 * {@code PUT} desligava uma família de regra globalmente; e {@code /v1/tenants/{id}/risk-config}
 * resolve o tenant pelo <b>path</b>, o que permitia a qualquer chamador editar a calibragem de
 * qualquer parceiro. Resolver pelo path é o comportamento correto para um endpoint administrativo
 * (o admin opera sobre outros tenants) — o que faltava era provar que quem chama é o admin.
 *
 * <p>Esta é a trava mínima, não a autenticação definitiva: uma chave estática compartilhada não
 * identifica <i>qual</i> operador agiu. A identidade por operador (e a trilha de autoria que ela
 * habilita) depende da autenticação por API key/mTLS ainda pendente.
 *
 * <p>Sem chave configurada: em {@code prod} a aplicação nem sobe ({@link AdminApiKeyReadinessGuard});
 * nos demais profiles o filtro fica inerte, para não travar dev/testes.
 */
@Component
@Order(2) // depois do CorrelationIdFilter, para que a negativa saia com id de correlação
public class AdminApiKeyFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Admin-Key";

  private static final Logger log = LoggerFactory.getLogger(AdminApiKeyFilter.class);

  /** {@code /v1/risk-rules[/...]} e {@code /v1/tenants/{id}/risk-config}. */
  private static final Pattern ADMIN_PATHS =
      Pattern.compile("^/v1/(risk-rules(/.*)?|tenants/[^/]+/risk-config)$");

  private final String configuredKey;

  public AdminApiKeyFilter(@Value("${barrier.admin.api-key:}") String configuredKey) {
    this.configuredKey = configuredKey;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !ADMIN_PATHS.matcher(request.getRequestURI()).matches();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (configuredKey.isBlank()) {
      // Guard de startup já barrou este caso em prod; aqui é dev/teste.
      chain.doFilter(request, response);
      return;
    }
    if (!matches(request.getHeader(HEADER))) {
      // Sem detalhe do motivo: não distinguir "ausente" de "errada" evita oráculo de sondagem.
      log.warn(
          "Acesso negado a endpoint administrativo {} {}", request.getMethod(), request.getRequestURI());
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response
          .getWriter()
          .write(
              "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                  + "\"detail\":\"Endpoint administrativo exige o header "
                  + HEADER
                  + "\"}");
      return;
    }
    chain.doFilter(request, response);
  }

  /** Comparação em tempo constante: um {@code equals} vazaria o prefixo correto por timing. */
  private boolean matches(String provided) {
    if (provided == null) {
      return false;
    }
    return MessageDigest.isEqual(
        provided.getBytes(StandardCharsets.UTF_8), configuredKey.getBytes(StandardCharsets.UTF_8));
  }
}
