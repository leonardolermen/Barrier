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
  private final DocumentType documentType;
  private final String documentDigits;
  private final String name;
  private AssessmentStatus status;
  private RiskLevel riskLevel;
  private String decision;
  private List<String> factors = List.of();
  private final Instant createdAt;
  private Instant completedAt;

  private Assessment(
      AssessmentId id,
      DocumentType documentType,
      String documentDigits,
      String name,
      Instant createdAt) {
    this.id = id;
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
  public static Assessment submit(DocumentType documentType, String rawDocument, String name) {
    Objects.requireNonNull(documentType, "documentType");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name obrigatório");
    }
    String digits =
        switch (documentType) {
          case CPF -> new Cpf(rawDocument).digits();
          case CNPJ -> new Cnpj(rawDocument).digits();
        };
    return new Assessment(AssessmentId.newId(), documentType, digits, name, Instant.now());
  }

  /** Reconstrói o agregado a partir da persistência. */
  public static Assessment rehydrate(
      AssessmentId id,
      DocumentType documentType,
      String documentDigits,
      String name,
      AssessmentStatus status,
      RiskLevel riskLevel,
      String decision,
      List<String> factors,
      Instant createdAt,
      Instant completedAt) {
    Assessment a = new Assessment(id, documentType, documentDigits, name, createdAt);
    a.status = status;
    a.riskLevel = riskLevel;
    a.decision = decision;
    a.factors = List.copyOf(factors);
    a.completedAt = completedAt;
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
}
