package com.barrier.riskengine.subject.profile.controller;

import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.domain.SubjectProfilePatch;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Atualização parcial de cadastro: todos os campos são opcionais — o cadastro é progressivo, a
 * obrigatoriedade por tipo de documento é avaliada por {@code RegistrationCompleteness}, não
 * nesta borda.
 */
public record UpdateProfileRequest(
    LocalDate birthDate,
    LocalDate foundingDate,
    String nationality,
    String occupation,
    BigDecimal declaredIncome,
    AddressRequest address,
    String phone,
    String email,
    String cnaeCode,
    String cnaeDescription,
    BigDecimal shareCapital,
    String legalRepresentativeName,
    String legalRepresentativeDocument,
    List<PartnerRequest> partners) {

  public SubjectProfilePatch toPatch() {
    return new SubjectProfilePatch(
        birthDate,
        foundingDate,
        nationality,
        occupation,
        declaredIncome,
        address == null
            ? null
            : new SubjectProfile.Address(
                address.street(),
                address.number(),
                address.complement(),
                address.district(),
                address.city(),
                address.state(),
                address.zipCode()),
        phone,
        email,
        cnaeCode,
        cnaeDescription,
        shareCapital,
        legalRepresentativeName,
        legalRepresentativeDocument,
        partners == null
            ? null
            : partners.stream()
                .map(
                    p ->
                        new SubjectProfile.Partner(
                            p.name(), p.legalEntity(), p.foreign(), p.qualification()))
                .toList());
  }

  public record AddressRequest(
      String street,
      String number,
      String complement,
      String district,
      String city,
      String state,
      String zipCode) {}

  public record PartnerRequest(String name, boolean legalEntity, boolean foreign, String qualification) {}
}
