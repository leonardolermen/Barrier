package com.barrier.riskengine.assurance.client.serpro;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.springframework.web.client.RestClient;

/**
 * JWKS de {@code GET pessoa-fisica/app/jwks} — chave pública que verifica a assinatura do
 * resultado biométrico.
 *
 * <p>Cacheado com TTL ({@code barrier.assurance.serpro.jwks-ttl}, default 1h — mantido no
 * namespace de assurance porque só a biometria usa JWKS/JWS): o desfecho
 * assinado (JWS) chega a cada poll, e buscar a chave a cada verificação seria uma chamada de rede
 * a mais por tentativa, sem necessidade — a chave não muda a cada resultado. <b>Rotação de chave
 * refaz a busca</b>: um {@code kid} não encontrado no cache dispara um refresh imediato antes de
 * desistir, porque o cache pode estar servindo o conjunto de chaves anterior à rotação.
 */
class SerproJwksClient {

  private final RestClient restClient;
  private final Clock clock;
  private final Duration ttl;
  private final ReentrantLock lock = new ReentrantLock();

  private volatile Map<String, RSAPublicKey> cached = Map.of();
  private volatile Instant fetchedAt = Instant.MIN;

  SerproJwksClient(RestClient restClient, Clock clock, Duration ttl) {
    this.restClient = restClient;
    this.clock = clock;
    this.ttl = ttl;
  }

  /** Chave pelo {@code kid} do header do JWS. Refaz a busca uma vez se o kid não é conhecido. */
  Optional<RSAPublicKey> keyFor(String kid) {
    Map<String, RSAPublicKey> keys = keys();
    RSAPublicKey found = keys.get(kid);
    if (found != null) {
      return Optional.of(found);
    }
    // kid desconhecido: pode ser rotação de chave acontecendo agora — refaz mesmo dentro do TTL.
    return Optional.ofNullable(refresh().get(kid));
  }

  private Map<String, RSAPublicKey> keys() {
    if (!cached.isEmpty() && clock.instant().isBefore(fetchedAt.plus(ttl))) {
      return cached;
    }
    lock.lock();
    try {
      if (!cached.isEmpty() && clock.instant().isBefore(fetchedAt.plus(ttl))) {
        return cached;
      }
      return refresh();
    } finally {
      lock.unlock();
    }
  }

  private Map<String, RSAPublicKey> refresh() {
    lock.lock();
    try {
      JwksResponse response = restClient.get().uri("/pessoa-fisica/app/jwks").retrieve().body(JwksResponse.class);
      List<Jwk> keys = response == null || response.keys() == null ? List.of() : response.keys();
      cached =
          keys.stream()
              .filter(k -> "RS512".equals(k.alg()) && "RSA".equals(k.kty()))
              .collect(Collectors.toMap(Jwk::keyId, SerproJwksClient::toPublicKey, (a, b) -> a));
      fetchedAt = clock.instant();
      return cached;
    } finally {
      lock.unlock();
    }
  }

  private static RSAPublicKey toPublicKey(Jwk jwk) {
    try {
      var decoder = Base64.getUrlDecoder();
      var n = new java.math.BigInteger(1, decoder.decode(jwk.n()));
      var e = new java.math.BigInteger(1, decoder.decode(jwk.e()));
      KeyFactory factory = KeyFactory.getInstance("RSA");
      return (RSAPublicKey) factory.generatePublic(new RSAPublicKeySpec(n, e));
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("JWKS do Serpro com chave RSA ilegível", e);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record JwksResponse(List<Jwk> keys) {}

  /**
   * {@code kid} é opcional na doc verbatim do JWKS (não estava no schema colado no pedido, mas
   * apareceu na sondagem ao vivo do ambiente de demonstração — ver relatório). Ausente, a chave
   * fica indexada sob chave vazia; só funciona enquanto houver uma única chave publicada, que é o
   * caso observado.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Jwk(String use, String kty, String alg, String kid, String n, String e) {
    String keyId() {
      return kid == null ? "" : kid;
    }
  }
}
