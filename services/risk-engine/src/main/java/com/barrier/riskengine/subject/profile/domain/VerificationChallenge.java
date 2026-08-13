package com.barrier.riskengine.subject.profile.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Desafio de OTP: um código de uso único enviado ao canal declarado (telefone ou e-mail).
 *
 * <p>Três propriedades que não são detalhe:
 *
 * <ul>
 *   <li><b>o código nunca é guardado em claro</b> — só o hash. Quem lê a tabela (DBA, backup, dump
 *       de suporte) poderia confirmar telefone e e-mail de qualquer cliente, que é exatamente o
 *       controle que o OTP existe para impor. Mesma regra já aplicada às API keys dos tenants;
 *   <li><b>tentativas têm teto</b> — um código de 6 dígitos tem 10⁶ combinações e cai por força
 *       bruta em minutos se cada tentativa for de graça;
 *   <li><b>a comparação é em tempo constante</b> — comparar hashes com {@code equals} vaza, pelo
 *       tempo de resposta, quantos caracteres iniciais estavam certos.
 * </ul>
 */
public record VerificationChallenge(
    UUID id,
    UUID subjectId,
    String tenantId,
    VerifiableField field,
    String target,
    String codeHash,
    int attemptsLeft,
    Instant expiresAt,
    Instant consumedAt,
    Instant createdAt) {

  private static final SecureRandom RANDOM = new SecureRandom();

  /** Resultado de emitir um desafio: o registro a persistir e o código a enviar, uma única vez. */
  public record Issued(VerificationChallenge challenge, String code) {}

  public static Issued issue(
      UUID subjectId,
      String tenantId,
      VerifiableField field,
      String target,
      int maxAttempts,
      java.time.Duration ttl,
      Instant now) {
    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
    VerificationChallenge challenge =
        new VerificationChallenge(
            UUID.randomUUID(),
            subjectId,
            tenantId,
            field,
            target,
            hash(code),
            maxAttempts,
            now.plus(ttl),
            null,
            now);
    return new Issued(challenge, code);
  }

  public boolean usable(Instant now) {
    return consumedAt == null && attemptsLeft > 0 && now.isBefore(expiresAt);
  }

  public boolean matches(String code) {
    return MessageDigest.isEqual(
        hash(code).getBytes(StandardCharsets.UTF_8), codeHash.getBytes(StandardCharsets.UTF_8));
  }

  public VerificationChallenge consumed(Instant now) {
    return new VerificationChallenge(
        id, subjectId, tenantId, field, target, codeHash, attemptsLeft, expiresAt, now, createdAt);
  }

  public VerificationChallenge failedAttempt() {
    return new VerificationChallenge(
        id,
        subjectId,
        tenantId,
        field,
        target,
        codeHash,
        attemptsLeft - 1,
        expiresAt,
        consumedAt,
        createdAt);
  }

  public static String hash(String code) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponível na JVM", e);
    }
  }
}
