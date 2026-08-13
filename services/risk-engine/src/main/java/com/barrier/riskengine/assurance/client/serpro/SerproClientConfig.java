package com.barrier.riskengine.assurance.client.serpro;

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
 * Beans do provider Serpro/Datavalid — só existem quando {@code barrier.assurance.serpro.enabled}
 * é {@code true} (mesma condição do provider, para não abrir sockets/threads de cliente HTTP à
 * toa quando o provedor está desligado).
 */
@Configuration
@ConditionalOnProperty(name = "barrier.assurance.serpro.enabled", havingValue = "true")
class SerproClientConfig {

  /** Duplica {@code IdentityClientConfig#timeoutBounded}: mesmo motivo (connect timeout da JDK é
   * ilimitado por padrão), pacote diferente — não vale introduzir dependência entre configs de
   * módulos diferentes por uma função de 5 linhas. */
  private static JdkClientHttpRequestFactory timeoutBounded(Duration connect, Duration read) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connect).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(read);
    return factory;
  }

  @Bean
  RestClient serproTokenRestClient(
      @Value("${barrier.assurance.serpro.auth-base-url:https://gateway.apiserpro.serpro.gov.br}")
          String authBaseUrl,
      @Value("${barrier.assurance.serpro.connect-timeout:PT2S}") Duration connectTimeout,
      @Value("${barrier.assurance.serpro.read-timeout:PT6S}") Duration readTimeout) {
    return RestClient.builder()
        .baseUrl(authBaseUrl)
        .requestFactory(timeoutBounded(connectTimeout, readTimeout))
        .build();
  }

  @Bean
  RestClient serproDatavalidRestClient(
      @Value(
              "${barrier.assurance.serpro.base-url:"
                  + "https://gateway.apiserpro.serpro.gov.br/datavalid/v5}")
          String baseUrl,
      @Value("${barrier.assurance.serpro.connect-timeout:PT2S}") Duration connectTimeout,
      @Value("${barrier.assurance.serpro.read-timeout:PT10S}") Duration readTimeout) {
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(timeoutBounded(connectTimeout, readTimeout))
        .defaultHeader("Accept", "application/json")
        .build();
  }

  @Bean
  SerproTokenClient serproTokenClient(
      @Qualifier("serproTokenRestClient") RestClient tokenRestClient,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${barrier.assurance.serpro.consumer-key:}") String consumerKey,
      @Value("${barrier.assurance.serpro.consumer-secret:}") String consumerSecret,
      @Value("${barrier.assurance.serpro.token-margin:PT60S}") Duration tokenMargin) {
    return new SerproTokenClient(
        tokenRestClient, objectMapper, consumerKey, consumerSecret, clock, tokenMargin);
  }

  @Bean
  SerproJwksClient serproJwksClient(
      @Qualifier("serproDatavalidRestClient") RestClient restClient,
      Clock clock,
      @Value("${barrier.assurance.serpro.jwks-ttl:PT1H}") Duration jwksTtl) {
    return new SerproJwksClient(restClient, clock, jwksTtl);
  }

  @Bean
  SerproJwsVerifier serproJwsVerifier(SerproJwksClient jwksClient, ObjectMapper objectMapper) {
    return new SerproJwsVerifier(jwksClient, objectMapper);
  }
}
