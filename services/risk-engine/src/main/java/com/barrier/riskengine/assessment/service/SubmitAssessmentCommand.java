package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.DocumentType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Comando de entrada para submeter uma nova avaliação, no escopo de um tenant.
 *
 * @param idempotencyKey chave informada pelo cliente ({@code Idempotency-Key}); {@code null} quando
 *     ele não usa idempotência — nesse caso cada POST cria uma avaliação, como antes
 */
public record SubmitAssessmentCommand(
    String tenantId,
    DocumentType documentType,
    String document,
    String name,
    String idempotencyKey) {

  /** Sem chave: cada submissão é nova. Usado pelos chamadores internos e por testes. */
  public SubmitAssessmentCommand(
      String tenantId, DocumentType documentType, String document, String name) {
    this(tenantId, documentType, document, name, null);
  }

  public boolean hasIdempotencyKey() {
    return idempotencyKey != null && !idempotencyKey.isBlank();
  }

  /**
   * Impressão digital do conteúdo da submissão, para detectar reuso da mesma chave com requisição
   * diferente.
   *
   * <p>Recebe o documento já normalizado para que {@code 111.444.777-35} e {@code 11144477735} —
   * a mesma submissão escrita de dois jeitos — não sejam lidos como conflito.
   */
  String fingerprint(String normalizedDocument) {
    String canonical =
        String.join("|", tenantId, documentType.name(), normalizedDocument, name.trim());
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponível na JVM", e);
    }
  }
}
