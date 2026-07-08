package com.barrier.riskengine.screening.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Clientes HTTP das fontes de watchlist que baixam arquivos publicados (CGU, OFAC). */
@Configuration
class ScreeningClientConfig {

  @Bean
  RestClient cguRestClient(
      @Value("${barrier.watchlist.cgu.base-url:https://portaldatransparencia.gov.br}")
          String baseUrl) {
    return download(baseUrl, Duration.ofSeconds(60));
  }

  @Bean
  RestClient ofacRestClient(
      @Value("${barrier.watchlist.ofac.base-url:https://www.treasury.gov/ofac/downloads}")
          String baseUrl) {
    return download(baseUrl, Duration.ofSeconds(60));
  }

  private static RestClient download(String baseUrl, Duration readTimeout) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(readTimeout);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }
}
