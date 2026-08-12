package com.barrier.riskengine.subject.profile.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Mapeamento JPA do cadastro do subject (CMN 4.753). */
@Entity
@Table(name = "subject_profiles")
@Getter(AccessLevel.PACKAGE)
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubjectProfileEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  /** Tenant que declarou este cadastro; parte da chave lógica junto com {@code subject_id}. */
  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "birth_date")
  private LocalDate birthDate;

  @Column(name = "founding_date")
  private LocalDate foundingDate;

  @Column(name = "nationality", length = 60)
  private String nationality;

  @Column(name = "occupation", length = 120)
  private String occupation;

  @Column(name = "declared_income", precision = 16, scale = 2)
  private BigDecimal declaredIncome;

  @Column(name = "address_street", length = 200)
  private String addressStreet;

  @Column(name = "address_number", length = 20)
  private String addressNumber;

  @Column(name = "address_complement", length = 100)
  private String addressComplement;

  @Column(name = "address_district", length = 100)
  private String addressDistrict;

  @Column(name = "address_city", length = 100)
  private String addressCity;

  @Column(name = "address_state", length = 2)
  private String addressState;

  @Column(name = "address_zip_code", length = 10)
  private String addressZipCode;

  @Column(name = "phone", length = 30)
  private String phone;

  @Column(name = "email", length = 160)
  private String email;

  @Column(name = "cnae_code", length = 10)
  private String cnaeCode;

  @Column(name = "cnae_description", length = 200)
  private String cnaeDescription;

  @Column(name = "share_capital", precision = 16, scale = 2)
  private BigDecimal shareCapital;

  @Column(name = "legal_representative_name", length = 200)
  private String legalRepresentativeName;

  @Column(name = "legal_representative_document", length = 20)
  private String legalRepresentativeDocument;

  /** JSONB: o QSA de uma empresa grande passa de 4000 caracteres com folga (ver migration V026). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "partners_json")
  private String partnersJson;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

}
