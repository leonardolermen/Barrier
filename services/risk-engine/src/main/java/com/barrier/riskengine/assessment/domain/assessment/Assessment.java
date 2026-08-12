package com.barrier.riskengine.assessment.domain.assessment;

import com.barrier.commons.observability.Correlation;
import com.barrier.riskengine.assessment.domain.documents.Cnpj;
import com.barrier.riskengine.assessment.domain.documents.Cpf;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.domain.documents.Documents;
import com.barrier.riskengine.assessment.domain.exceptions.InvalidDocumentException;
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
  /** Correlação da requisição que criou a avaliação; restaurada no processamento assíncrono. */
  private String correlationId;
  private String reviewedBy;
  private String reviewedByKey;
  private int attempts;
  private String lastError;
  private Instant nextAttemptAt;
  private String reviewReason;
  private Instant reviewedAt;

  /** Por que esta avaliação existe; ver {@link AssessmentOrigin}. */
  private AssessmentOrigin origin = AssessmentOrigin.ONBOARDING;

  /** Fonte e versão da lista que disparou o rescreening; nulo em avaliação de onboarding. */
  private String originDetail;

  /**
   * Versão da linha no momento em que o agregado foi carregado. O domínio não usa este número para
   * nada — ele existe porque, sem carregá-lo, a checagem de concorrência otimista era feita contra
   * um valor lido no instante da escrita, o que a tornava decorativa. Ver
   * {@code AssessmentRepositoryImpl.save}.
   */
  private long version;

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
    Assessment assessment =
        new Assessment(
            AssessmentId.newId(), tenantId, subjectId, documentType, digits, name, Instant.now());
    assessment.correlationId = Correlation.currentOrNew();
    return assessment;
  }

  /**
   * Avaliação criada pelo monitoramento contínuo. Mesmo agregado e mesmo pipeline do onboarding —
   * só a trilha registra que quem pediu foi o motor, e por causa de qual mudança de lista.
   *
   * @param originDetail fonte e versão da lista que disparou (ex.: {@code OFAC@2026-08-12})
   */
  public static Assessment rescreen(
      String tenantId,
      String subjectId,
      DocumentType documentType,
      String rawDocument,
      String name,
      String originDetail) {
    Assessment assessment = submit(tenantId, subjectId, documentType, rawDocument, name);
    assessment.origin = AssessmentOrigin.RESCREENING;
    assessment.originDetail = originDetail;
    return assessment;
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
      Instant reviewedAt,
      int attempts,
      String lastError,
      Instant nextAttemptAt,
      long version,
      String correlationId,
      AssessmentOrigin origin,
      String originDetail) {
    Assessment a =
        new Assessment(id, tenantId, subjectId, documentType, documentDigits, name, createdAt);
    a.correlationId = correlationId;
    a.origin = origin == null ? AssessmentOrigin.ONBOARDING : origin;
    a.originDetail = originDetail;
    a.version = version;
    a.reviewedByKey = reviewedByKey;
    a.attempts = attempts;
    a.lastError = lastError;
    a.nextAttemptAt = nextAttemptAt;
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

  /**
   * Registra uma tentativa de processamento que falhou.
   *
   * <p>Mesma forma do {@code Delivery.markFailed} da entrega de webhook — o problema é o mesmo
   * (tentar de novo com parcimônia e desistir em algum momento), e resolver diferente em cada
   * lugar só criaria duas maneiras de raciocinar sobre a mesma coisa.
   *
   * <p>Esgotadas as tentativas, o status vira {@link AssessmentStatus#FALHA_PROCESSAMENTO}: sem
   * isso a avaliação ficava EM_ANALISE indefinidamente, reprocessada a cada ciclo, indistinguível
   * de uma que ainda vai concluir.
   */
  public void recordFailure(String error, int maxAttempts, Instant nextAttemptAt) {
    if (this.status != AssessmentStatus.EM_ANALISE) {
      throw new IllegalStateException("Só avaliação em análise registra falha: " + id.asString());
    }
    this.attempts++;
    this.lastError = truncate(error);
    if (this.attempts >= maxAttempts) {
      this.status = AssessmentStatus.FALHA_PROCESSAMENTO;
      this.nextAttemptAt = null;
      this.completedAt = Instant.now();
    } else {
      this.nextAttemptAt = nextAttemptAt;
    }
  }

  /** A coluna é limitada; um stack trace longo não pode derrubar a gravação da própria falha. */
  private static String truncate(String error) {
    if (error == null) {
      return "erro sem mensagem";
    }
    return error.length() <= 500 ? error : error.substring(0, 497) + "...";
  }

  public boolean isPending() {
    return status == AssessmentStatus.EM_ANALISE;
  }

  /** Versão da linha quando este agregado foi carregado; detalhe de persistência. */
  public long version() {
    return version;
  }

  public String correlationId() {
    return correlationId;
  }

  public int attempts() {
    return attempts;
  }

  public String lastError() {
    return lastError;
  }

  public Instant nextAttemptAt() {
    return nextAttemptAt;
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

  public AssessmentOrigin origin() {
    return origin;
  }

  public String originDetail() {
    return originDetail;
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
