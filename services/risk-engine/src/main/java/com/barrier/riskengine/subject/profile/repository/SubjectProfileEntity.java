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

/** Mapeamento JPA do cadastro do subject (CMN 4.753). */
@Entity
@Table(name = "subject_profiles")
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

  protected SubjectProfileEntity() {
    // JPA
  }

  UUID getId() {
    return id;
  }

  void setId(UUID id) {
    this.id = id;
  }

  UUID getSubjectId() {
    return subjectId;
  }

  void setSubjectId(UUID subjectId) {
    this.subjectId = subjectId;
  }

  String getTenantId() {
    return tenantId;
  }

  void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  LocalDate getBirthDate() {
    return birthDate;
  }

  void setBirthDate(LocalDate birthDate) {
    this.birthDate = birthDate;
  }

  LocalDate getFoundingDate() {
    return foundingDate;
  }

  void setFoundingDate(LocalDate foundingDate) {
    this.foundingDate = foundingDate;
  }

  String getNationality() {
    return nationality;
  }

  void setNationality(String nationality) {
    this.nationality = nationality;
  }

  String getOccupation() {
    return occupation;
  }

  void setOccupation(String occupation) {
    this.occupation = occupation;
  }

  BigDecimal getDeclaredIncome() {
    return declaredIncome;
  }

  void setDeclaredIncome(BigDecimal declaredIncome) {
    this.declaredIncome = declaredIncome;
  }

  String getAddressStreet() {
    return addressStreet;
  }

  void setAddressStreet(String addressStreet) {
    this.addressStreet = addressStreet;
  }

  String getAddressNumber() {
    return addressNumber;
  }

  void setAddressNumber(String addressNumber) {
    this.addressNumber = addressNumber;
  }

  String getAddressComplement() {
    return addressComplement;
  }

  void setAddressComplement(String addressComplement) {
    this.addressComplement = addressComplement;
  }

  String getAddressDistrict() {
    return addressDistrict;
  }

  void setAddressDistrict(String addressDistrict) {
    this.addressDistrict = addressDistrict;
  }

  String getAddressCity() {
    return addressCity;
  }

  void setAddressCity(String addressCity) {
    this.addressCity = addressCity;
  }

  String getAddressState() {
    return addressState;
  }

  void setAddressState(String addressState) {
    this.addressState = addressState;
  }

  String getAddressZipCode() {
    return addressZipCode;
  }

  void setAddressZipCode(String addressZipCode) {
    this.addressZipCode = addressZipCode;
  }

  String getPhone() {
    return phone;
  }

  void setPhone(String phone) {
    this.phone = phone;
  }

  String getEmail() {
    return email;
  }

  void setEmail(String email) {
    this.email = email;
  }

  String getCnaeCode() {
    return cnaeCode;
  }

  void setCnaeCode(String cnaeCode) {
    this.cnaeCode = cnaeCode;
  }

  String getCnaeDescription() {
    return cnaeDescription;
  }

  void setCnaeDescription(String cnaeDescription) {
    this.cnaeDescription = cnaeDescription;
  }

  BigDecimal getShareCapital() {
    return shareCapital;
  }

  void setShareCapital(BigDecimal shareCapital) {
    this.shareCapital = shareCapital;
  }

  String getLegalRepresentativeName() {
    return legalRepresentativeName;
  }

  void setLegalRepresentativeName(String legalRepresentativeName) {
    this.legalRepresentativeName = legalRepresentativeName;
  }

  String getLegalRepresentativeDocument() {
    return legalRepresentativeDocument;
  }

  void setLegalRepresentativeDocument(String legalRepresentativeDocument) {
    this.legalRepresentativeDocument = legalRepresentativeDocument;
  }

  String getPartnersJson() {
    return partnersJson;
  }

  void setPartnersJson(String partnersJson) {
    this.partnersJson = partnersJson;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  Instant getUpdatedAt() {
    return updatedAt;
  }

  void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
