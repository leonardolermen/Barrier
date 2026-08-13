package com.barrier.riskengine.serpro;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Token OAuth2 client_credentials do gateway Serpro ({@code POST /token}), com cache local
 * respeitando {@code expires_in}.
 *
 * <p><b>Plumbing compartilhado</b> — extraído de {@code assurance.client.serpro} porque um segundo
 * consumidor (validação cadastral, em {@code subject.profile}) usa a mesma credencial e o mesmo
 * token do mesmo gateway Serpro; {@code subject.profile} não pode depender de {@code assurance}
 * (regra de módulo), então este pacote neutro (não pertence a nenhum dos dois) é o lugar comum.
 * Não vive em {@code commons} porque isso arrastaria o {@code RestClient}/dependência de
 * infraestrutura HTTP no `commons` compartilhado com módulos que não precisam dela — mesmo
 * raciocínio já registrado no CLAUDE.md para o `AdminApiKeyFilter`.
 *
 * <p><b>Não pede token por requisição</b> — {@code expires_in} típico é da ordem de uma hora
 * (~3295s no exemplo da doc oficial); pedir um novo a cada chamada sobrecarregaria o endpoint de
 * autenticação sem necessidade. Uma margem de segurança ({@code barrier.serpro.token-margin},
 * default 60s) renova antes do vencimento exato, para não arriscar usar um token que expira no
 * meio de uma chamada em voo.
 *
 * <p>{@code synchronized} via lock explícito, não {@code synchronized} na assinatura: evita
 * segurar o monitor do bean inteiro (compartilhado com outras chamadas concorrentes) durante o
 * round-trip de rede da renovação.
 */
public class SerproTokenClient {

  private final RestClient tokenRestClient;
  private final ObjectMapper objectMapper;
  private final String consumerKey;
  private final String consumerSecret;
  private final Clock clock;
  private final Duration margin;
  private final ReentrantLock lock = new ReentrantLock();

  private volatile String cachedToken;
  private volatile Instant expiresAt = Instant.MIN;

  public SerproTokenClient(
      RestClient tokenRestClient,
      ObjectMapper objectMapper,
      String consumerKey,
      String consumerSecret,
      Clock clock,
      Duration margin) {
    this.tokenRestClient = tokenRestClient;
    this.objectMapper = objectMapper;
    this.consumerKey = consumerKey;
    this.consumerSecret = consumerSecret;
    this.clock = clock;
    this.margin = margin;
  }

  public String token() {
    Instant now = clock.instant();
    if (cachedToken != null && now.isBefore(expiresAt)) {
      return cachedToken;
    }
    lock.lock();
    try {
      // Outra thread pode ter renovado enquanto esperávamos o lock.
      now = clock.instant();
      if (cachedToken != null && now.isBefore(expiresAt)) {
        return cachedToken;
      }
      return renew(now);
    } finally {
      lock.unlock();
    }
  }

  private String renew(Instant now) {
    String basic = Base64.getEncoder().encodeToString((consumerKey + ":" + consumerSecret).getBytes());
    String body =
        tokenRestClient
            .post()
            .uri("/token")
            .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=client_credentials")
            .retrieve()
            .body(String.class);
    TokenResponse response = objectMapper.readValue(body, TokenResponse.class);
    cachedToken = response.accessToken();
    expiresAt = now.plusSeconds(Math.max(0, response.expiresIn() - margin.toSeconds()));
    return cachedToken;
  }

  /** Corpo documentado de {@code POST /token}: {@code scope, token_type, expires_in, access_token}. */
  private record TokenResponse(
      String scope,
      @JsonProperty("token_type") String tokenType,
      @JsonProperty("expires_in") long expiresIn,
      @JsonProperty("access_token") String accessToken) {}
}
