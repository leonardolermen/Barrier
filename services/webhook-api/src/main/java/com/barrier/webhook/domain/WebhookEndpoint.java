package com.barrier.webhook.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

/**
 * Endereço de callback de um tenant e o segredo que assina as entregas dele.
 *
 * <p>São as duas metades do mesmo problema: o endereço faz o resultado do KYC chegar ao cliente
 * certo, e o segredo próprio faz só o Barrier conseguir provar que aquele resultado é dele. Com
 * segredo compartilhado, quem conhecesse o de um tenant forjaria callbacks para todos.
 *
 * @param previousSecret segredo anterior, válido até {@code previousSecretUntil}; {@code null} fora
 *     de uma rotação
 */
public record WebhookEndpoint(
    String tenantId,
    String targetUrl,
    String secret,
    String previousSecret,
    Instant previousSecretUntil,
    boolean active,
    Instant createdAt,
    Instant updatedAt) {

  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
  private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int SECRET_BYTES = 32;

  public static WebhookEndpoint register(String tenantId, String targetUrl) {
    return register(tenantId, targetUrl, newSecret());
  }

  /**
   * Registro preservando um segredo já existente — é o caminho de <b>atualizar a URL</b>. Trocar o
   * segredo aqui quebraria a verificação do cliente sem ninguém ter pedido rotação.
   */
  public static WebhookEndpoint register(String tenantId, String targetUrl, String secret) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenantId obrigatório");
    }
    validate(targetUrl);
    Instant now = Instant.now();
    return new WebhookEndpoint(tenantId, targetUrl.trim(), secret, null, null, true, now, now);
  }

  /**
   * Gera um segredo novo e mantém o atual válido por {@code overlap}.
   *
   * <p>A janela é o que torna a rotação uma operação sem downtime: durante ela a entrega vai
   * assinada pelos dois, e o cliente troca a chave quando puder. Sem sobreposição, rotacionar
   * obrigaria a combinar um instante exato com cada parceiro — e na prática ninguém rotaciona.
   */
  public WebhookEndpoint rotateSecret(Duration overlap) {
    return new WebhookEndpoint(
        tenantId,
        targetUrl,
        newSecret(),
        secret,
        secret == null ? null : Instant.now().plus(overlap),
        active,
        createdAt,
        Instant.now());
  }

  public WebhookEndpoint deactivate() {
    return new WebhookEndpoint(
        tenantId,
        targetUrl,
        secret,
        previousSecret,
        previousSecretUntil,
        false,
        createdAt,
        Instant.now());
  }

  /** Segredo anterior enquanto a janela de rotação vale; {@code null} fora dela. */
  public String usablePreviousSecret() {
    if (previousSecret == null || previousSecretUntil == null) {
      return null;
    }
    return Instant.now().isBefore(previousSecretUntil) ? previousSecret : null;
  }

  private static String newSecret() {
    byte[] bytes = new byte[SECRET_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Recusa destino que não seja HTTP(S) absoluto e exige TLS fora de host local: o payload leva
   * documento, nome e o veredito de PLD-FT do cliente final, e em texto claro qualquer ponto do
   * caminho lê tudo — a assinatura HMAC prova origem, não confidencialidade.
   */
  private static void validate(String targetUrl) {
    if (targetUrl == null || targetUrl.isBlank()) {
      throw new IllegalArgumentException("targetUrl obrigatório");
    }
    URI uri;
    try {
      uri = new URI(targetUrl.trim());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("targetUrl inválida: " + targetUrl, e);
    }
    if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())) {
      throw new IllegalArgumentException("targetUrl deve ser http ou https: " + targetUrl);
    }
    if (uri.getHost() == null) {
      throw new IllegalArgumentException("targetUrl sem host: " + targetUrl);
    }
    if ("http".equalsIgnoreCase(uri.getScheme()) && !LOCAL_HOSTS.contains(uri.getHost())) {
      throw new IllegalArgumentException(
          "targetUrl sem TLS: " + targetUrl + " — http só é aceito para host local (dev)");
    }
  }
}
