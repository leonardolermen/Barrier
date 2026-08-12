package com.barrier.riskengine.identity.repository;

import com.barrier.riskengine.identity.domain.IdentityStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento JPA de uma verificação de identidade.
 *
 * <p>Acessores gerados pelo Lombok em nível de pacote — a entidade não vaza da camada de
 * repositório. Sem {@code @Data}/{@code @EqualsAndHashCode}/{@code @ToString}: em entidade JPA
 * eles disparam lazy loading e quebram o contrato de identidade do Hibernate.
 */
@Entity
@Table(name = "identity_checks")
@Getter(AccessLevel.PACKAGE)
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdentityCheckEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "assessment_id", nullable = false, length = 64)
  private String assessmentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private IdentityStatus status;

  @Column(name = "provider", nullable = false, length = 60)
  private String provider;

  @Column(name = "detail", length = 400)
  private String detail;

  @Column(name = "checked_at", nullable = false)
  private Instant checkedAt;

  /** Id da consulta no provedor (QueryId); nulo quando a fonte não fornece. Ver V031. */
  @Column(name = "provider_reference", length = 120)
  private String providerReference;

  /** Resposta do bureau, com redação dos campos sensíveis. JSONB — ver V031. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "raw_response", columnDefinition = "jsonb")
  private String rawResponse;

}
