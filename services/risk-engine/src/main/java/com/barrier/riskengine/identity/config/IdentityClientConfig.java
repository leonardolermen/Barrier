package com.barrier.riskengine.identity.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Beans dos clientes de bureau de identidade. */
@Configuration
class IdentityClientConfig {

  @Bean
  RestClient brasilApiRestClient(
      @Value("${barrier.identity.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(Duration.ofSeconds(6));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  @Bean
  RestClient bigBoostRestClient(
      @Value("${barrier.identity.bigboost.base-url:https://plataforma.bigdatacorp.com.br}")
          String baseUrl,
      @Value("${barrier.identity.bigboost.access-token:}") String accessToken,
      @Value("${barrier.identity.bigboost.token-id:}") String tokenId) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(Duration.ofSeconds(6));
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(factory)
        .defaultHeader("AccessToken", accessToken)
        .defaultHeader("TokenId", tokenId)
        .defaultHeader("Accept", "application/json")
        .build();
  }
}
