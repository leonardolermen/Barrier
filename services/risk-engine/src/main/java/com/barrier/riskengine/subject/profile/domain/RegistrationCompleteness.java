package com.barrier.riskengine.subject.profile.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

  /**
   * Compatibilidade para chamadores que só querem presença (nenhuma verificação conhecida).
   *
   * <p>Existe para os testes e para leitura administrativa do cadastro. O caminho da decisão usa a
   * sobrecarga com os campos verificados — presença sozinha é o gate que esta mudança veio
   * corrigir.
   */
  public static RegistrationCompleteness evaluate(String documentType, SubjectProfile profile) {
    return check(documentType, profile, Set.of(), false);
  }

  /**
   * Gate de aprovação automática: campo tem de estar preenchido <b>e</b>, quando for verificável,
   * verificado.
   *
   * <p>Preenchimento sozinho é satisfeito por dado plausível e inventado — nascimento verossímil,
   * telefone que existe mas não é do cliente, endereço de terceiro. O checklist rodava, produzia
   * evidência de que rodou, e não impedia nada.
   *
   * @param verifiedFields campos com verificação válida para os valores que estão no cadastro
   *     agora (ver {@code FieldVerificationService.verifiedFields})
   */
  public static RegistrationCompleteness evaluate(
      String documentType, SubjectProfile profile, Set<VerifiableField> verifiedFields) {
    return check(documentType, profile, verifiedFields, true);
  }

  /**
   * @param requireVerification distingue "não exigir verificação" de "nada verificado" — tratar os
   *     dois como conjunto vazio faria a sobrecarga de compatibilidade reprovar todo cadastro
   */
  private static RegistrationCompleteness check(
      String documentType,
      SubjectProfile profile,
      Set<VerifiableField> verifiedFields,
      boolean requireVerification) {
    List<String> missing = new ArrayList<>();
    switch (documentType) {
      case "CPF" -> {
        if (profile.birthDate() == null) missing.add("data de nascimento");
        else if (requireVerification && !verifiedFields.contains(VerifiableField.BIRTH_DATE)) {
          missing.add("data de nascimento não conferida com o bureau");
        }
        if (isBlank(profile.nationality())) missing.add("nacionalidade");
        if (isBlank(profile.occupation())) missing.add("ocupação");
        if (profile.address() == null) missing.add("endereço");
        // Canal de contato verificado é exigência de PF: é o que sustenta comunicação com o
        // titular e o que torna o cadastro contestável por ele. Basta um dos dois — cobrar
        // telefone E e-mail travaria cliente legítimo que só tem um.
        if (requireVerification
            && !verifiedFields.contains(VerifiableField.PHONE)
            && !verifiedFields.contains(VerifiableField.EMAIL)) {
          missing.add("telefone ou e-mail verificado");
        }
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
