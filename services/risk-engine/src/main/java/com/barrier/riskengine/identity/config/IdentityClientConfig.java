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
}
