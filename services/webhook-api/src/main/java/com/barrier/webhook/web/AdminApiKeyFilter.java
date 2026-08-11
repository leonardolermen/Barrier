package com.barrier.webhook.web;

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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exige {@code X-Admin-Key} em {@code /v1/webhook-endpoints}. Mesma forma do filtro homônimo da
 * risk-engine — a duplicação é deliberada: são serviços separados, com deploy e configuração
 * próprios, e compartilhar o filtro pelo {@code commons} arrastaria dependência de web para um
 * módulo que hoje só carrega evento e outbox.
 *
 * <p>Quem escreve aqui decide para onde vai o veredito de KYC de um parceiro; sem trava, qualquer
 * um que alcance a porta redireciona os callbacks de qualquer tenant para um endpoint próprio.
 *
 * <p>Sem chave configurada: em {@code prod} a aplicação nem sobe
 * ({@link AdminApiKeyReadinessGuard}); nos demais profiles o filtro fica inerte, para não travar
 * dev/testes.
 */
@Component
public class AdminApiKeyFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Admin-Key";

  private static final Logger log = LoggerFactory.getLogger(AdminApiKeyFilter.class);
  private static final Pattern ADMIN_PATHS = Pattern.compile("^/v1/webhook-endpoints(/.*)?$");

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
          "Acesso negado a endpoint administrativo {} {}",
          request.getMethod(),
          request.getRequestURI());
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
