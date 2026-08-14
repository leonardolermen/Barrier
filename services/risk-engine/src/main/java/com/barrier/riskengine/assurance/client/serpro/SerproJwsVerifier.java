package com.barrier.riskengine.assurance.client.serpro;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifica a assinatura do {@code jws} devolvido por {@code POST pessoa-fisica/app/resultado}
 * contra o JWKS do Serpro, e decodifica o payload.
 *
 * <p><b>Verificar a assinatura é obrigatório, não defensivo</b> (ver design da task): aceitar o
 * JWS sem verificar pagaria de novo o preço que não guardar a imagem evitou — "a face bateu 98%"
 * voltaria a ser afirmação nossa sobre nós mesmos em vez de prova de terceiro assinada.
 */
class SerproJwsVerifier {

  private final SerproJwksClient jwksClient;
  private final ObjectMapper objectMapper;

  SerproJwsVerifier(SerproJwksClient jwksClient, ObjectMapper objectMapper) {
    this.jwksClient = jwksClient;
    this.objectMapper = objectMapper;
  }

  /** @return o payload decodificado, ou vazio se a assinatura não confere ou o formato é inválido. */
  Optional<Payload> verify(String compact) {
    String[] parts = compact.split("\\.");
    if (parts.length != 3) {
      return Optional.empty();
    }
    Base64.Decoder decoder = Base64.getUrlDecoder();
    Header header;
    try {
      header = objectMapper.readValue(decoder.decode(parts[0]), Header.class);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    if (!"RS512".equals(header.alg())) {
      // Recusar em vez de tentar outro algoritmo: aceitar "none" ou trocar de algoritmo é a
      // classe de vulnerabilidade clássica de verificação de JWT/JWS.
      return Optional.empty();
    }
    String kid = header.kid() == null ? "" : header.kid();
    Optional<java.security.interfaces.RSAPublicKey> key = jwksClient.keyFor(kid);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
    byte[] signature = decoder.decode(parts[2]);
    if (!verifySignature(signingInput, signature, key.get())) {
      return Optional.empty();
    }
    try {
      Payload payload = objectMapper.readValue(decoder.decode(parts[1]), Payload.class);
      return Optional.of(payload);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static boolean verifySignature(byte[] signingInput, byte[] signature, PublicKey key) {
    try {
      Signature verifier = Signature.getInstance("SHA512withRSA");
      verifier.initVerify(key);
      verifier.update(signingInput);
      return verifier.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Header(String alg, String kid, String typ) {}

  /**
   * @param sub <b>é o CPF</b> (ver design da task) — nunca logar sem máscara.
   * @param seloBiometrico único valor observado na doc é {@code "A"} (aprovado); outros valores
   *     não foram verificados contra a API real — ver relatório.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Payload(
      String iss,
      String sub,
      String pin,
      @JsonProperty("selo_biometrico") String seloBiometrico,
      @JsonProperty("face_similaridade") Double faceSimilaridade,
      String device) {}
}
