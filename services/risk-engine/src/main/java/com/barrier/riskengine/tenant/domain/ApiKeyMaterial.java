package com.barrier.riskengine.tenant.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Geração, formatação e verificação do material criptográfico de uma API key.
 *
 * <p>Formato: {@code brr_<keyId>_<secret>}. O prefixo identifica a origem em vazamentos (permite
 * varredura automatizada em repositórios públicos, como fazem Stripe e GitHub); o {@code keyId}
 * é público e indexado; o segredo tem 256 bits de entropia.
 *
 * <p><b>Por que SHA-256 e não bcrypt/argon2:</b> aqueles existem para senhas, que têm entropia
 * baixa e precisam de custo artificial contra força bruta offline. Um segredo aleatório de 256
 * bits não é atacável por força bruta, e um KDF caro aqui só adicionaria latência a <i>toda</i>
 * requisição. A comparação é feita em tempo constante.
 */
public final class ApiKeyMaterial {

  private static final String PREFIX = "brr";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int KEY_ID_BYTES = 8;
  private static final int SECRET_BYTES = 32;

  private ApiKeyMaterial() {}

  /**
   * Chave recém-emitida: o valor em claro existe só neste objeto, para ser entregue ao cliente.
   *
   * @param keyId parte pública
   * @param secret segredo em claro — nunca persistir
   * @param secretHash o que vai para o banco
   */
  public record Generated(String keyId, String secret, String secretHash) {

    /** Valor completo a entregar ao cliente, uma única vez. */
    public String presentedValue() {
      return PREFIX + "_" + keyId + "_" + secret;
    }
  }

  public static Generated generate() {
    String keyId = HexFormat.of().formatHex(randomBytes(KEY_ID_BYTES));
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(SECRET_BYTES));
    return new Generated(keyId, secret, hash(secret));
  }

  /**
   * Extrai as partes de uma chave apresentada. Vazio quando o formato não confere.
   *
   * <p>O limite 3 no {@code split} é essencial, não estilo: o alfabeto base64url inclui {@code _},
   * então o segredo contém underscores em cerca de metade das chaves geradas. Sem o limite, essas
   * chaves eram partidas em 4+ pedaços e recusadas — falha de autenticação intermitente, que passa
   * em teste isolado e quebra de forma aparentemente aleatória.
   */
  public static Optional<Presented> parse(String presented) {
    if (presented == null) {
      return Optional.empty();
    }
    String[] parts = presented.trim().split("_", 3);
    if (parts.length != 3 || !PREFIX.equals(parts[0]) || parts[1].isBlank() || parts[2].isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new Presented(parts[1], parts[2]));
  }

  /** Partes de uma chave apresentada numa requisição. */
  public record Presented(String keyId, String secret) {}

  public static String hash(String secret) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponível", e);
    }
  }

  /** Comparação em tempo constante: {@code equals} vazaria o prefixo correto por timing. */
  public static boolean matches(String secret, String expectedHash) {
    return MessageDigest.isEqual(
        hash(secret).getBytes(StandardCharsets.UTF_8),
        expectedHash.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    return bytes;
  }
}
