package com.barrier.riskengine.subject.profile.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Checklist mínimo de cadastro exigido pela Resolução CMN 4.753, por tipo de documento. Não é
 * validação de negócio na borda (a API aceita cadastro parcial e progressivo) — é o gate usado
 * antes de uma avaliação poder ser aprovada automaticamente.
 *
 * <p>O tipo de documento fica como String (mesma convenção de {@code Subject}) para manter o
 * módulo independente do módulo assessment.
 *
 * @param complete {@code true} se todos os campos mínimos do tipo estão preenchidos
 * @param missingFields nomes legíveis dos campos que faltam, para explicabilidade
 */
public record RegistrationCompleteness(boolean complete, List<String> missingFields) {

  public RegistrationCompleteness {
    missingFields = List.copyOf(missingFields);
  }

  public static RegistrationCompleteness evaluate(String documentType, SubjectProfile profile) {
    List<String> missing = new ArrayList<>();
    switch (documentType) {
      case "CPF" -> {
        if (profile.birthDate() == null) missing.add("data de nascimento");
        if (isBlank(profile.nationality())) missing.add("nacionalidade");
        if (isBlank(profile.occupation())) missing.add("ocupação");
        if (profile.address() == null) missing.add("endereço");
      }
      case "CNPJ" -> {
        if (profile.foundingDate() == null) missing.add("data de fundação");
        if (isBlank(profile.cnaeCode())) missing.add("CNAE");
        if (profile.address() == null) missing.add("endereço");
        if (isBlank(profile.legalRepresentativeName())
            || isBlank(profile.legalRepresentativeDocument())) {
          missing.add("representante legal");
        }
      }
      default -> throw new IllegalArgumentException("documentType desconhecido: " + documentType);
    }
    return new RegistrationCompleteness(missing.isEmpty(), missing);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
