package com.barrier.riskengine.identity.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Beans dos clientes de bureau de identidade. */
@Configuration
class IdentityClientConfig {

  /**
   * Só o read timeout estava configurado. O connect timeout do {@code HttpClient} da JDK é
   * <b>ilimitado</b> por padrão, e read timeout não cobre um TCP que nunca completa o handshake —
   * o caso comum de firewall que descarta pacotes em vez de recusar a conexão. Como a decisão roda
   * num {@code @Scheduled}, uma conexão pendurada travava a thread indefinidamente: o serviço
   * parava de decidir sem lançar exceção, sem retry e com {@code /actuator/health} verde.
   */
  private static JdkClientHttpRequestFactory timeoutBounded(Duration connect, Duration read) {
    // O connect timeout do HttpClient da JDK é do cliente, não da requisição — por isso ele não
    // aparece no request factory, e por isso ficou de fora quando só o read timeout foi definido.
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connect).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(read);
    return factory;
  }

  @Bean
  RestClient brasilApiRestClient(
      @Value("${barrier.identity.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl,
      @Value("${barrier.identity.brasilapi.connect-timeout:PT2S}") Duration connectTimeout,
      @Value("${barrier.identity.brasilapi.read-timeout:PT6S}") Duration readTimeout) {
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(timeoutBounded(connectTimeout, readTimeout))
        .build();
  }

  @Bean
  RestClient bigBoostRestClient(
      @Value("${barrier.identity.bigboost.base-url:https://plataforma.bigdatacorp.com.br}")
          String baseUrl,
      @Value("${barrier.identity.bigboost.access-token:}") String accessToken,
      @Value("${barrier.identity.bigboost.token-id:}") String tokenId,
      @Value("${barrier.identity.bigboost.connect-timeout:PT2S}") Duration connectTimeout,
      @Value("${barrier.identity.bigboost.read-timeout:PT6S}") Duration readTimeout) {
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(timeoutBounded(connectTimeout, readTimeout))
        .defaultHeader("AccessToken", accessToken)
        .defaultHeader("TokenId", tokenId)
        .defaultHeader("Accept", "application/json")
        .build();
  }
}
