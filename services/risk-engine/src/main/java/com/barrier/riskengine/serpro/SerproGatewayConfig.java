package com.barrier.riskengine.serpro;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Beans de conectividade com o gateway Serpro — token OAuth2 e o {@code RestClient} do
 * {@code datavalid/v5} — compartilhados por qualquer integração Serpro do projeto (hoje:
 * biometria facial em {@code assurance}, validação cadastral em {@code subject.profile}).
 * Mesma credencial, mesmo token, mesmo gateway: as duas frentes ligam/desligam juntas por
 * {@code barrier.serpro.enabled} — desligado por padrão, dev/testes usam os stubs de cada módulo.
 */
@Configuration
@ConditionalOnProperty(name = "barrier.serpro.enabled", havingValue = "true")
public class SerproGatewayConfig {

  private static JdkClientHttpRequestFactory timeoutBounded(Duration connect, Duration read) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connect).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(read);
    return factory;
  }

  @Bean
  RestClient serproAuthRestClient(
      @Value("${barrier.serpro.auth-base-url:https://gateway.apiserpro.serpro.gov.br}")
          String authBaseUrl,
      @Value("${barrier.serpro.connect-timeout:PT2S}") Duration connectTimeout,
      @Value("${barrier.serpro.read-timeout:PT6S}") Duration readTimeout) {
    return RestClient.builder()
        .baseUrl(authBaseUrl)
        .requestFactory(timeoutBounded(connectTimeout, readTimeout))
        .build();
  }

  @Bean
  public RestClient serproDatavalidRestClient(
      @Value("${barrier.serpro.base-url:https://gateway.apiserpro.serpro.gov.br/datavalid/v5}")
          String baseUrl,
      @Value("${barrier.serpro.connect-timeout:PT2S}") Duration connectTimeout,
      @Value("${barrier.serpro.read-timeout:PT10S}") Duration readTimeout) {
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(timeoutBounded(connectTimeout, readTimeout))
        .defaultHeader("Accept", "application/json")
        .build();
  }

  @Bean
  public SerproTokenClient serproTokenClient(
      @Qualifier("serproAuthRestClient") RestClient authRestClient,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${barrier.serpro.consumer-key:}") String consumerKey,
      @Value("${barrier.serpro.consumer-secret:}") String consumerSecret,
      @Value("${barrier.serpro.token-margin:PT60S}") Duration tokenMargin) {
    return new SerproTokenClient(authRestClient, objectMapper, consumerKey, consumerSecret, clock, tokenMargin);
  }
}
