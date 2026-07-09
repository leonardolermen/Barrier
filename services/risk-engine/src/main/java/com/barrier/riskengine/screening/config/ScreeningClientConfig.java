package com.barrier.riskengine.screening.config;

import java.net.http.HttpClient;
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
    // As fontes publicam via CDN/S3 e respondem 302 para uma URL pré-assinada; sem seguir o
    // redirect o download vem vazio. NORMAL segue (não faz downgrade HTTPS→HTTP).
    HttpClient httpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(readTimeout);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }
}
