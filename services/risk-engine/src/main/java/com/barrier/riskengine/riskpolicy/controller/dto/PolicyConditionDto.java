package com.barrier.riskengine.riskpolicy.controller.dto;

import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Representação de fio de uma {@link Condition} na API pública -- o mesmo formato de {@code
 * PolicyRuleJson.ConditionWire} (persistência), mas uma cópia deliberada e não o mesmo tipo.
 *
 * <p>As duas dizem coisas diferentes: o contrato HTTP é o que o parceiro escreve e não pode
 * quebrar sem aviso; o formato de armazenamento é interno e pode mudar de forma independente
 * (renomear um discriminador de {@code rules_json} não é breaking change de API, e vice-versa).
 * Reaproveitar o mesmo tipo acoplaria os dois por acidente.
 *
 * <p>Como lá: carrega só o {@code id} do campo (nunca o {@link
 * com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField} inteiro), então quem escreve a
 * requisição não tem como forjar {@code type}/{@code exposure} de um campo -- só {@code
 * PolicyDtoMapper.toDomain} resolve o id contra o {@code FieldCatalog}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PolicyConditionDto.And.class, name = "AND"),
  @JsonSubTypes.Type(value = PolicyConditionDto.Or.class, name = "OR"),
  @JsonSubTypes.Type(value = PolicyConditionDto.Not.class, name = "NOT"),
  @JsonSubTypes.Type(value = PolicyConditionDto.Comparison.class, name = "COMPARISON"),
  @JsonSubTypes.Type(value = PolicyConditionDto.AnyOf.class, name = "ANY_OF")
})
public sealed interface PolicyConditionDto {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record And(@NotEmpty @Valid List<PolicyConditionDto> operands) implements PolicyConditionDto {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Or(@NotEmpty @Valid List<PolicyConditionDto> operands) implements PolicyConditionDto {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Not(@NotNull @Valid PolicyConditionDto operand) implements PolicyConditionDto {}

  /** {@code field} é o id do catálogo ({@code company.openingDate}), nunca o campo resolvido. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Comparison(
      @NotBlank String field, @NotNull Operator op, @NotNull @Valid PolicyLiteralDto value)
      implements PolicyConditionDto {}

  /** {@code listField} é o id de um campo {@code LIST} do catálogo ({@code company.partners}). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record AnyOf(@NotBlank String listField, @NotNull @Valid PolicyConditionDto each)
      implements PolicyConditionDto {}
}
