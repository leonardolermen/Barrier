package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentOrigin;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
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
    String idempotencyKey,
    AssessmentOrigin origin,
    String originDetail) {

  /** Submissão do parceiro, com a chave de idempotência que ele mandou (ou {@code null}). */
  public SubmitAssessmentCommand(
      String tenantId,
      DocumentType documentType,
      String document,
      String name,
      String idempotencyKey) {
    this(
        tenantId,
        documentType,
        document,
        name,
        idempotencyKey,
        AssessmentOrigin.ONBOARDING,
        null);
  }

  /** Sem chave: cada submissão é nova. Usado pelos chamadores internos e por testes. */
  public SubmitAssessmentCommand(
      String tenantId, DocumentType documentType, String document, String name) {
    this(tenantId, documentType, document, name, null);
  }

  /**
   * Reavaliação criada pelo monitoramento contínuo.
   *
   * <p>Sem {@code Idempotency-Key} de propósito: a chave existe para o parceiro poder reenviar a
   * mesma requisição sem duplicar avaliação. Aqui quem pede é o motor, e o ponto da reavaliação é
   * justamente decidir de novo — reaproveitar uma decisão anterior devolveria o resultado tomado
   * antes de o cliente estar na lista.
   */
  public static SubmitAssessmentCommand rescreening(
      String tenantId,
      DocumentType documentType,
      String document,
      String name,
      String originDetail) {
    return new SubmitAssessmentCommand(
        tenantId, documentType, document, name, null, AssessmentOrigin.RESCREENING, originDetail);
  }

  /**
   * Reavaliação pedida por uma verificação de documentoscopia/biometria (assurance).
   *
   * <p>Sem {@code Idempotency-Key} pelo mesmo motivo do rescreening: quem pede é o próprio motor,
   * e o ponto é decidir de novo a partir do que a verificação encontrou.
   */
  public static SubmitAssessmentCommand assurance(
      String tenantId,
      DocumentType documentType,
      String document,
      String name,
      String originDetail) {
    return new SubmitAssessmentCommand(
        tenantId, documentType, document, name, null, AssessmentOrigin.ASSURANCE, originDetail);
  }

  /**
   * Reavaliação pedida por alteração material do cadastro (ADR-0019).
   *
   * @param originDetail campos que mudaram, separados por vírgula — nunca os valores. O que mudou
   *     é trilha; o que virou é dado pessoal, e já está no cadastro.
   */
  public static SubmitAssessmentCommand profilePatch(
      String tenantId,
      DocumentType documentType,
      String document,
      String name,
      String originDetail) {
    return new SubmitAssessmentCommand(
        tenantId, documentType, document, name, null, AssessmentOrigin.PROFILE_PATCH, originDetail);
  }

  /** Reavaliação periódica de rotina (re-KYC), disparada pelo job diário. */
  public static SubmitAssessmentCommand periodicReview(
      String tenantId,
      DocumentType documentType,
      String document,
      String name,
      String originDetail) {
    return new SubmitAssessmentCommand(
        tenantId,
        documentType,
        document,
        name,
        null,
        AssessmentOrigin.PERIODIC_REVIEW,
        originDetail);
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
