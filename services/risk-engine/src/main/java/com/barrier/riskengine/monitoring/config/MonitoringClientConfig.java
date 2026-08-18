package com.barrier.riskengine.monitoring.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Cliente HTTP do canal de alertas. */
@Configuration
class MonitoringClientConfig {

  /**
   * Timeouts curtos e nos dois lados. O notificador roda dentro do ciclo do
   * {@code AlertEvaluator}: um POST pendurado no PagerDuty travaria a thread do scheduler e o
   * monitoramento pararia de monitorar — a falha mais irônica possível. O connect timeout do
   * {@code HttpClient} da JDK é ilimitado por padrão, e read timeout não cobre handshake que nunca
   * completa (mesma armadilha documentada em {@code IdentityClientConfig}).
   */
  @Bean
  RestClient pagerDutyRestClient(
      @Value("${barrier.monitoring.pagerduty.base-url:https://events.pagerduty.com}") String baseUrl,
      @Value("${barrier.monitoring.pagerduty.connect-timeout:PT2S}") Duration connectTimeout,
      @Value("${barrier.monitoring.pagerduty.read-timeout:PT5S}") Duration readTimeout) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(readTimeout);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }
}
