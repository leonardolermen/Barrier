package com.barrier.riskengine.subject.profile.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Dados de cadastro do subject exigidos pela Resolução CMN 4.753. 1:1 com {@code Subject}: o
 * {@code Subject} é a identidade mínima (dedup por documento), este é o cadastro completo.
 *
 * <p>Campos são nullable e se dividem por tipo de documento: {@code birthDate}/{@code
 * nationality}/{@code occupation} são de PF; {@code foundingDate}/{@code cnaeCode}/{@code
 * shareCapital}/{@code legalRepresentative*}/{@code partners} são de PJ. {@code address},
 * {@code phone}, {@code email} e {@code declaredIncome} valem para os dois.
 */
public record SubjectProfile(
    UUID id,
    UUID subjectId,
    LocalDate birthDate,
    LocalDate foundingDate,
    String nationality,
    String occupation,
    BigDecimal declaredIncome,
    Address address,
    String phone,
    String email,
    String cnaeCode,
    String cnaeDescription,
    BigDecimal shareCapital,
    String legalRepresentativeName,
    String legalRepresentativeDocument,
    List<Partner> partners,
    Instant createdAt,
    Instant updatedAt) {

  public SubjectProfile {
    partners = partners == null ? List.of() : List.copyOf(partners);
  }

  /** Perfil vazio recém-criado para um subject, pronto para receber dados progressivamente. */
  public static SubjectProfile blank(UUID subjectId) {
    Instant now = Instant.now();
    return new SubjectProfile(
        UUID.randomUUID(),
        subjectId,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        now,
        now);
  }

  public record Address(
      String street,
      String number,
      String complement,
      String district,
      String city,
      String state,
      String zipCode) {}

  /** Sócio do quadro societário (QSA), mesmo shape do bureau em {@code identity.domain}. */
  public record Partner(String name, boolean legalEntity, boolean foreign, String qualification) {}
}
