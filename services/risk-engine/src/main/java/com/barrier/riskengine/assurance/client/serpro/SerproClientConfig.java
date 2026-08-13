package com.barrier.riskengine.assurance.client.serpro;

import com.barrier.riskengine.serpro.SerproTokenClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Beans específicos da biometria facial (JWKS/JWS) — a conectividade genérica com o gateway
 * Serpro (token, {@code RestClient} do datavalid) mudou para {@code
 * com.barrier.riskengine.serpro.SerproGatewayConfig}, compartilhada com a validação cadastral de
 * {@code subject.profile}. Só existe quando {@code barrier.serpro.enabled} é {@code true} (mesma
 * condição do provider e do gateway, para não abrir sockets/threads de cliente HTTP à toa quando
 * o provedor está desligado).
 */
@Configuration
@ConditionalOnProperty(name = "barrier.serpro.enabled", havingValue = "true")
class SerproClientConfig {

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
