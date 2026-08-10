package com.barrier.riskengine.subject.profile.repository;

import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Implementação JPA do repositório de cadastro. O quadro societário ({@code partners}) é
 * serializado em JSON na coluna {@code partners_json}, mesmo padrão de {@code hits_json} /
 * {@code results_json}.
 */
@Repository
class SubjectProfileRepositoryImpl implements SubjectProfileRepository {

  private static final TypeReference<List<SubjectProfile.Partner>> PARTNER_LIST =
      new TypeReference<>() {};

  private final SubjectProfileJpaRepository jpa;
  private final ObjectMapper objectMapper;

  SubjectProfileRepositoryImpl(SubjectProfileJpaRepository jpa, ObjectMapper objectMapper) {
    this.jpa = jpa;
    this.objectMapper = objectMapper;
  }

  @Override
  public SubjectProfile save(SubjectProfile profile) {
    SubjectProfileEntity e = new SubjectProfileEntity();
    e.setId(profile.id());
    e.setSubjectId(profile.subjectId());
    e.setTenantId(profile.tenantId());
    e.setBirthDate(profile.birthDate());
    e.setFoundingDate(profile.foundingDate());
    e.setNationality(profile.nationality());
    e.setOccupation(profile.occupation());
    e.setDeclaredIncome(profile.declaredIncome());
    if (profile.address() != null) {
      e.setAddressStreet(profile.address().street());
      e.setAddressNumber(profile.address().number());
      e.setAddressComplement(profile.address().complement());
      e.setAddressDistrict(profile.address().district());
      e.setAddressCity(profile.address().city());
      e.setAddressState(profile.address().state());
      e.setAddressZipCode(profile.address().zipCode());
    }
    e.setPhone(profile.phone());
    e.setEmail(profile.email());
    e.setCnaeCode(profile.cnaeCode());
    e.setCnaeDescription(profile.cnaeDescription());
    e.setShareCapital(profile.shareCapital());
    e.setLegalRepresentativeName(profile.legalRepresentativeName());
    e.setLegalRepresentativeDocument(profile.legalRepresentativeDocument());
    e.setPartnersJson(objectMapper.writeValueAsString(profile.partners()));
    e.setCreatedAt(profile.createdAt());
    e.setUpdatedAt(profile.updatedAt());
    return toDomain(jpa.save(e));
  }

  @Override
  public Optional<SubjectProfile> findBySubjectIdAndTenantId(UUID subjectId, String tenantId) {
    return jpa.findBySubjectIdAndTenantId(subjectId, tenantId).map(this::toDomain);
  }

  private SubjectProfile toDomain(SubjectProfileEntity e) {
    SubjectProfile.Address address =
        e.getAddressStreet() == null
                && e.getAddressNumber() == null
                && e.getAddressCity() == null
            ? null
            : new SubjectProfile.Address(
                e.getAddressStreet(),
                e.getAddressNumber(),
                e.getAddressComplement(),
                e.getAddressDistrict(),
                e.getAddressCity(),
                e.getAddressState(),
                e.getAddressZipCode());
    List<SubjectProfile.Partner> partners =
        e.getPartnersJson() == null
            ? List.of()
            : objectMapper.readValue(e.getPartnersJson(), PARTNER_LIST);
    return new SubjectProfile(
        e.getId(),
        e.getSubjectId(),
        e.getTenantId(),
        e.getBirthDate(),
        e.getFoundingDate(),
        e.getNationality(),
        e.getOccupation(),
        e.getDeclaredIncome(),
        address,
        e.getPhone(),
        e.getEmail(),
        e.getCnaeCode(),
        e.getCnaeDescription(),
        e.getShareCapital(),
        e.getLegalRepresentativeName(),
        e.getLegalRepresentativeDocument(),
        partners,
        e.getCreatedAt(),
        e.getUpdatedAt());
  }
}
