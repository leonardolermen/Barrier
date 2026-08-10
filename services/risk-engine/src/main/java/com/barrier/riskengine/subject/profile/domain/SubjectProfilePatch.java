package com.barrier.riskengine.subject.profile.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Atualização parcial de um {@link SubjectProfile}: cada campo não-nulo sobrescreve o valor
 * existente, campos nulos preservam o que já estava salvo. Usado tanto pelo cliente (via {@code
 * PUT /v1/subjects/{document}/profile}) quanto pelo enriquecimento automático a partir do bureau
 * de identidade (dados objetivos de PJ).
 */
public record SubjectProfilePatch(
    LocalDate birthDate,
    LocalDate foundingDate,
    String nationality,
    String occupation,
    BigDecimal declaredIncome,
    SubjectProfile.Address address,
    String phone,
    String email,
    String cnaeCode,
    String cnaeDescription,
    BigDecimal shareCapital,
    String legalRepresentativeName,
    String legalRepresentativeDocument,
    List<SubjectProfile.Partner> partners) {

  /** Aplica este patch sobre um perfil existente, preservando campos não informados. */
  public SubjectProfile applyTo(SubjectProfile current) {
    return new SubjectProfile(
        current.id(),
        current.subjectId(),
        current.tenantId(),
        firstNonNull(birthDate, current.birthDate()),
        firstNonNull(foundingDate, current.foundingDate()),
        firstNonNull(nationality, current.nationality()),
        firstNonNull(occupation, current.occupation()),
        firstNonNull(declaredIncome, current.declaredIncome()),
        firstNonNull(address, current.address()),
        firstNonNull(phone, current.phone()),
        firstNonNull(email, current.email()),
        firstNonNull(cnaeCode, current.cnaeCode()),
        firstNonNull(cnaeDescription, current.cnaeDescription()),
        firstNonNull(shareCapital, current.shareCapital()),
        firstNonNull(legalRepresentativeName, current.legalRepresentativeName()),
        firstNonNull(legalRepresentativeDocument, current.legalRepresentativeDocument()),
        partners != null && !partners.isEmpty() ? partners : current.partners(),
        current.createdAt(),
        java.time.Instant.now());
  }

  private static <T> T firstNonNull(T candidate, T fallback) {
    return candidate != null ? candidate : fallback;
  }
}
