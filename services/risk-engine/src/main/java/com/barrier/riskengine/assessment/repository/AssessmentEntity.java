package com.barrier.riskengine.assessment.repository;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Mapeamento JPA da avaliação. Não vaza para fora da camada de repositório. */
@Entity
@Table(name = "assessments")
@Getter(AccessLevel.PACKAGE)
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssessmentEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "subject_id")
  private UUID subjectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "document_type", nullable = false, length = 10)
  private DocumentType documentType;

  @Column(name = "document_value", nullable = false, length = 20)
  private String documentValue;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private AssessmentStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk_level", length = 10)
  private RiskLevel riskLevel;

  @Column(name = "decision", length = 200)
  private String decision;

  /** TEXT: lista de linhas legíveis, não JSON — mas sem teto, pelo mesmo motivo (V026). */
  @Column(name = "factors")
  private String factors;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  /** Quem escreve é o Hibernate — daí o {@code @Setter(NONE)}. */
  @Version
  @Column(name = "version", nullable = false)
  @Setter(AccessLevel.NONE)
  private long version;

  @Column(name = "claimed_at")
  private Instant claimedAt;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "last_error", length = 500)
  private String lastError;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "reviewed_by_key", length = 120)
  private String reviewedByKey;

  @Column(name = "reviewed_by", length = 200)
  private String reviewedBy;

  @Column(name = "review_reason", length = 500)
  private String reviewReason;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "correlation_id", length = 64)
  private String correlationId;

}
