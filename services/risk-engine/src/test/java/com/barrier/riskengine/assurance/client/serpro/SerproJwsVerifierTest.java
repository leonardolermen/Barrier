package com.barrier.riskengine.assurance.client.serpro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Verificação de assinatura é obrigatória (ver design da task): este teste prova que um JWS
 * assinado por uma chave <b>diferente</b> da publicada no JWKS é recusado — aceitar sem verificar
 * devolveria "a face bateu" como afirmação nossa, não prova de terceiro.
 *
 * <p>O JWKS real do ambiente de demonstração foi sondado ao vivo (ver relatório) e confirma o
 * formato usado aqui: {@code {"keys":[{"use":"sig","kty":"RSA","alg":"RS512","kid":"1","n":...,
 * "e":"AQAB"}]}} — inclusive o campo {@code kid}, que não estava no schema colado no pedido
 * original.
 */
class SerproJwsVerifierTest {

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private SerproJwsVerifier verifierWithJwks(RSAPublicKey published) {
    String n = urlEncode(published.getModulus().toByteArray());
    String e = urlEncode(published.getPublicExponent().toByteArray());
    server
        .expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
            "/pessoa-fisica/app/jwks"))
        .andRespond(
            org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                "{\"keys\":[{\"use\":\"sig\",\"kty\":\"RSA\",\"alg\":\"RS512\",\"kid\":\"1\",\"n\":\""
                    + n
                    + "\",\"e\":\""
                    + e
                    + "\"}]}",
                org.springframework.http.MediaType.APPLICATION_JSON));
    SerproJwksClient jwksClient =
        new SerproJwksClient(builder.build(), Clock.systemUTC(), Duration.ofHours(1));
    return new SerproJwsVerifier(jwksClient, objectMapper);
  }

  private static String urlEncode(byte[] bigIntegerBytes) {
    // BigInteger.toByteArray() pode ter um byte de sinal 0x00 à esquerda que a codificação
    // "unsigned big-endian" do JWKS não usa — removê-lo é o inverso exato de como
    // SerproJwksClient reconstrói o BigInteger a partir do JWKS.
    byte[] bytes = bigIntegerBytes;
    if (bytes.length > 1 && bytes[0] == 0) {
      bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sign(RSAPrivateKey key, String header, String payload) throws Exception {
    String signingInput =
        Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes(StandardCharsets.UTF_8))
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    Signature signature = Signature.getInstance("SHA512withRSA");
    signature.initSign(key);
    signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    return signingInput + "." + sig;
  }

  @Test
  void aceitaJwsAssinadoPelaChavePublicadaNoJwks() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    SerproJwsVerifier verifier = verifierWithJwks((RSAPublicKey) pair.getPublic());

    String header = "{\"alg\":\"RS512\",\"kid\":\"1\",\"typ\":\"JWT\"}";
    String payload =
        "{\"iss\":\"Datavalid\",\"sub\":\"11144477735\",\"pin\":\"123456\","
            + "\"selo_biometrico\":\"A\",\"face_similaridade\":98.5,\"device\":\"android\"}";
    String jws = sign((RSAPrivateKey) pair.getPrivate(), header, payload);

    Optional<SerproJwsVerifier.Payload> result = verifier.verify(jws);

    assertThat(result).isPresent();
    assertThat(result.get().seloBiometrico()).isEqualTo("A");
    assertThat(result.get().sub()).isEqualTo("11144477735");
    assertThat(result.get().faceSimilaridade()).isEqualTo(98.5);
    server.verify();
  }

  /**
   * Regressão do motivo de existir: um JWS assinado por outra chave (ex.: forjado, ou chave
   * antiga pós-rotação) tem de ser recusado, não aceito como se a assinatura não importasse.
   */
  @Test
  void recusaJwsAssinadoPorChaveDiferenteDaPublicada() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair publicado = gen.generateKeyPair();
    KeyPair impostor = gen.generateKeyPair();
    SerproJwsVerifier verifier = verifierWithJwks((RSAPublicKey) publicado.getPublic());

    String header = "{\"alg\":\"RS512\",\"kid\":\"1\",\"typ\":\"JWT\"}";
    String payload = "{\"iss\":\"Datavalid\",\"sub\":\"11144477735\",\"selo_biometrico\":\"A\"}";
    String jws = sign((RSAPrivateKey) impostor.getPrivate(), header, payload);

    assertThat(verifier.verify(jws)).isEmpty();
    server.verify();
  }

  @Test
  void recusaAlgoritmoDiferenteDeRs512() {
    SerproJwksClient jwksClient =
        new SerproJwksClient(builder.build(), Clock.systemUTC(), Duration.ofHours(1));
    SerproJwsVerifier verifier = new SerproJwsVerifier(jwksClient, objectMapper);
    String header =
        Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"x\"}".getBytes(StandardCharsets.UTF_8));

    assertThat(verifier.verify(header + "." + payload + ".")).isEmpty();
  }

  @Test
  void recusaFormatoInvalido() {
    SerproJwksClient jwksClient =
        new SerproJwksClient(builder.build(), Clock.systemUTC(), Duration.ofHours(1));
    SerproJwsVerifier verifier = new SerproJwsVerifier(jwksClient, objectMapper);

    assertThat(verifier.verify("nao-e-um-jws")).isEmpty();
  }
}
