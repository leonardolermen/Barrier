package com.barrier.riskengine.assessment.domain;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Agregado de uma avaliação de risco. Objeto de domínio puro (sem JPA): a persistência é
 * feita por um adapter no pacote {@code repository}.
 */
public class Assessment {

  private final AssessmentId id;
  private final String tenantId;
  private final String subjectId;
  private final DocumentType documentType;
  private final String documentDigits;
  private final String name;
  private AssessmentStatus status;
  private RiskLevel riskLevel;
  private String decision;
  private List<String> factors = List.of();
  private final Instant createdAt;
  private Instant completedAt;
  private String reviewedBy;
  private String reviewedByKey;
  private String reviewReason;
  private Instant reviewedAt;

  private Assessment(
      AssessmentId id,
      String tenantId,
      String subjectId,
      DocumentType documentType,
      String documentDigits,
      String name,
      Instant createdAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.subjectId = subjectId;
    this.documentType = documentType;
    this.documentDigits = documentDigits;
    this.name = name;
    this.status = AssessmentStatus.EM_ANALISE;
    this.createdAt = createdAt;
  }

  /**
   * Cria uma avaliação nova em {@link AssessmentStatus#EM_ANALISE}, validando o documento.
   *
   * @throws InvalidDocumentException se o CPF/CNPJ for inválido
   */
  public static Assessment submit(
      String tenantId, String subjectId, DocumentType documentType, String rawDocument, String name) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(documentType, "documentType");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name obrigatório");
    }
    String digits = Documents.normalize(documentType, rawDocument);
    return new Assessment(
        AssessmentId.newId(), tenantId, subjectId, documentType, digits, name, Instant.now());
  }

  /** Reconstrói o agregado a partir da persistência. */
  public static Assessment rehydrate(
      AssessmentId id,
      String tenantId,
      String subjectId,
      DocumentType documentType,
      String documentDigits,
      String name,
      AssessmentStatus status,
      RiskLevel riskLevel,
      String decision,
      List<String> factors,
      Instant createdAt,
      Instant completedAt,
      String reviewedBy,
      String reviewedByKey,
      String reviewReason,
      Instant reviewedAt) {
    Assessment a =
        new Assessment(id, tenantId, subjectId, documentType, documentDigits, name, createdAt);
    a.reviewedByKey = reviewedByKey;
    a.status = status;
    a.riskLevel = riskLevel;
    a.decision = decision;
    a.factors = List.copyOf(factors);
    a.completedAt = completedAt;
    a.reviewedBy = reviewedBy;
    a.reviewReason = reviewReason;
    a.reviewedAt = reviewedAt;
    return a;
  }

  /** Conclui a avaliação a partir do estado EM_ANALISE, com os fatores explicáveis da decisão. */
  public void complete(
      RiskLevel riskLevel, AssessmentStatus finalStatus, String decision, List<String> factors) {
    if (this.status != AssessmentStatus.EM_ANALISE) {
      throw new IllegalStateException("Avaliação já concluída: " + id.asString());
    }
    if (finalStatus == AssessmentStatus.EM_ANALISE) {
      throw new IllegalArgumentException("Status final não pode ser EM_ANALISE");
    }
    this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
    this.status = finalStatus;
    this.decision = decision;
    this.factors = List.copyOf(factors);
    this.completedAt = Instant.now();
  }

  /**
   * Decisão humana de uma avaliação em revisão (EDD). Só é válida a partir de EM_REVISAO.
   *
   * @param approve true aprova (APROVADO), false reprova (REPROVADO)
   */
  public void decide(boolean approve, String reviewedBy, String reviewedByKey, String reason) {
    if (this.status != AssessmentStatus.EM_REVISAO) {
      throw new IllegalStateException("Avaliação não está em revisão: " + id.asString());
    }
    if (reviewedBy == null || reviewedBy.isBlank()) {
      throw new IllegalArgumentException("reviewedBy obrigatório");
    }
    this.status = approve ? AssessmentStatus.APROVADO : AssessmentStatus.REPROVADO;
    this.decision = (approve ? "Aprovado" : "Reprovado") + " em revisão por " + reviewedBy;
    this.reviewedBy = reviewedBy;
    this.reviewedByKey = reviewedByKey;
    this.reviewReason = reason;
    this.reviewedAt = Instant.now();
  }

  public boolean isPending() {
    return status == AssessmentStatus.EM_ANALISE;
  }

  /** Documento mascarado para log/exposição (nunca expor os dígitos completos). */
  public String maskedDocument() {
    return switch (documentType) {
      case CPF -> new Cpf(documentDigits).masked();
      case CNPJ -> new Cnpj(documentDigits).masked();
    };
  }

  public AssessmentId id() {
    return id;
  }

  public String tenantId() {
    return tenantId;
  }

  public String subjectId() {
    return subjectId;
  }

  public DocumentType documentType() {
    return documentType;
  }

  public String documentDigits() {
    return documentDigits;
  }

  public String name() {
    return name;
  }

  public AssessmentStatus status() {
    return status;
  }

  public RiskLevel riskLevel() {
    return riskLevel;
  }

  public String decision() {
    return decision;
  }

  public List<String> factors() {
    return factors;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant completedAt() {
    return completedAt;
  }

  public String reviewedBy() {
    return reviewedBy;
  }

  /**
   * Rótulo da credencial que tomou a decisão. Diferente de {@link #reviewedBy()}, que é texto
   * autodeclarado: este o sistema garante, e é revogável.
   */
  public String reviewedByKey() {
    return reviewedByKey;
  }

  public String reviewReason() {
    return reviewReason;
  }

  public Instant reviewedAt() {
    return reviewedAt;
  }
}
