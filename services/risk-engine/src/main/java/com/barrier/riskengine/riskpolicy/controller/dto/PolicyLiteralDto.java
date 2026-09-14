package com.barrier.riskengine.riskpolicy.controller.dto;

import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;

/**
 * Representação de fio de um {@link Literal} na API pública. Mesma razão de {@link
 * PolicyConditionDto} não ser {@code PolicyRuleJson.LiteralWire}: contrato público e formato de
 * armazenamento têm ciclos de mudança independentes.
 *
 * <p>Mesmo cuidado de nomeação de {@link PolicyConditionDto}: cada variante leva {@code
 * @Schema(name = ...)} para não publicar {@code Text}/{@code Number}/{@code None} nus na seção
 * compartilhada de schemas, onde uma colisão futura com outro tipo aninhado do mesmo nome seria
 * sobrescrita em silêncio pelo springdoc.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PolicyLiteralDto.Text.class, name = "TEXT"),
  @JsonSubTypes.Type(value = PolicyLiteralDto.Number.class, name = "NUMBER"),
  @JsonSubTypes.Type(value = PolicyLiteralDto.Bool.class, name = "BOOL"),
  @JsonSubTypes.Type(value = PolicyLiteralDto.Date.class, name = "DATE"),
  @JsonSubTypes.Type(value = PolicyLiteralDto.Duration.class, name = "DURATION"),
  @JsonSubTypes.Type(value = PolicyLiteralDto.TextSet.class, name = "TEXT_SET"),
  @JsonSubTypes.Type(value = PolicyLiteralDto.None.class, name = "NONE")
})
public sealed interface PolicyLiteralDto {

  @Schema(name = "PolicyLiteralText")
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Text(String value) implements PolicyLiteralDto {}

  @Schema(name = "PolicyLiteralNumber")
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Number(BigDecimal value) implements PolicyLiteralDto {}

  @Schema(name = "PolicyLiteralBool")
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Bool(boolean value) implements PolicyLiteralDto {}

  @Schema(name = "PolicyLiteralDate")
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Date(LocalDate value) implements PolicyLiteralDto {}

  /**
   * ISO-8601 ({@code P6M}, {@code P18Y}) -- o mesmo formato de {@code
   * barrier.identity.reuse.ttl}.
   */
  @Schema(name = "PolicyLiteralDuration")
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Duration(Period value) implements PolicyLiteralDto {}

  @Schema(name = "PolicyLiteralTextSet")
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TextSet(List<String> values) implements PolicyLiteralDto {}

  /** Para {@code IS_NULL}/{@code IS_NOT_NULL}, que não comparam contra nada. */
  @Schema(name = "PolicyLiteralNone")
  @JsonIgnoreProperties(ignoreUnknown = true)
  record None() implements PolicyLiteralDto {}
}
