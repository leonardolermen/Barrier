package com.barrier.riskengine.riskpolicy.repository;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializa a árvore de {@link PolicyRule} para {@code rules_json} e de volta.
 *
 * <p><b>{@link PolicyField} nunca é serializado por inteiro.</b> Ele carrega um {@code
 * Function<Object, Object>} (não serializável) e, mais importante, {@code type}/{@code exposure}/
 * {@code parentListId} — se esses viajassem no JSON, quem escreve a linha na mão (ou explora um
 * bug de outra camada) poderia declarar um campo sensível como {@code exposure = BY_VALUE} e
 * vazar valor pela evidência. O formato de fio ({@link ConditionWire.ComparisonWire}, {@link
 * ConditionWire.AnyOfWire}) só carrega o {@code id} do campo, como string — o tipo não tem ONDE
 * colocar um type/exposure forjado. A leitura sempre resolve pelo {@link FieldCatalog}; um id que
 * não resolve é {@link UnknownPolicyFieldException}, nunca um campo silenciosamente incompleto.
 */
@Component
public class PolicyRuleJson {

  private static final TypeReference<List<RuleWire>> RULE_LIST = new TypeReference<>() {};

  private final ObjectMapper objectMapper;
  private final FieldCatalog catalog;

  public PolicyRuleJson(ObjectMapper objectMapper, FieldCatalog catalog) {
    this.objectMapper = objectMapper;
    this.catalog = catalog;
  }

  public String toJson(List<PolicyRule> rules) {
    List<RuleWire> wire = rules.stream().map(this::toWire).toList();
    return objectMapper.writeValueAsString(wire);
  }

  public List<PolicyRule> fromJson(String json) {
    List<RuleWire> wire = objectMapper.readValue(json, RULE_LIST);
    return wire.stream().map(this::toDomain).toList();
  }

  // ---------------------------------------------------------------------
  // PolicyRule <-> RuleWire
  // ---------------------------------------------------------------------

  private RuleWire toWire(PolicyRule rule) {
    return new RuleWire(
        rule.code(),
        rule.name(),
        toWire(rule.when()),
        rule.score(),
        rule.severity(),
        rule.recommendation());
  }

  private PolicyRule toDomain(RuleWire wire) {
    return new PolicyRule(
        wire.code(),
        wire.name(),
        toDomain(wire.when()),
        wire.score(),
        wire.severity(),
        wire.recommendation());
  }

  // ---------------------------------------------------------------------
  // Condition <-> ConditionWire
  // ---------------------------------------------------------------------

  private ConditionWire toWire(Condition condition) {
    return switch (condition) {
      case Condition.And and ->
          new ConditionWire.AndWire(and.operands().stream().map(this::toWire).toList());
      case Condition.Or or ->
          new ConditionWire.OrWire(or.operands().stream().map(this::toWire).toList());
      case Condition.Not not -> new ConditionWire.NotWire(toWire(not.operand()));
      case Condition.Comparison c ->
          new ConditionWire.ComparisonWire(c.field().id(), c.op(), toWire(c.value()));
      case Condition.AnyOf anyOf ->
          new ConditionWire.AnyOfWire(anyOf.listField().id(), toWire(anyOf.each()));
    };
  }

  private Condition toDomain(ConditionWire wire) {
    return switch (wire) {
      case ConditionWire.AndWire and ->
          new Condition.And(and.operands().stream().map(this::toDomain).toList());
      case ConditionWire.OrWire or ->
          new Condition.Or(or.operands().stream().map(this::toDomain).toList());
      case ConditionWire.NotWire not -> new Condition.Not(toDomain(not.operand()));
      case ConditionWire.ComparisonWire c ->
          new Condition.Comparison(resolveField(c.fieldId()), c.op(), toDomain(c.value()));
      case ConditionWire.AnyOfWire anyOf ->
          new Condition.AnyOf(resolveField(anyOf.listFieldId()), toDomain(anyOf.each()));
    };
  }

  /**
   * Único ponto de resolução de {@link PolicyField} na leitura. Nunca constrói um {@link
   * PolicyField} a partir do JSON — sempre pede ao catálogo, que é a fonte de verdade de tipo,
   * exposição e extrator. Id que não resolve é erro alto: uma política gravada contra uma versão
   * antiga do catálogo referenciando campo removido é uma corrupção de dado que precisa aparecer,
   * não ser engolida como "regra não dispara".
   */
  private PolicyField resolveField(String fieldId) {
    return catalog
        .find(fieldId)
        .orElseThrow(
            () ->
                new UnknownPolicyFieldException(
                    "campo '"
                        + fieldId
                        + "' nao existe no catalogo de politica (versao "
                        + catalog.version()
                        + ") -- politica gravada referencia um id que nao resolve mais"));
  }

  // ---------------------------------------------------------------------
  // Literal <-> LiteralWire
  // ---------------------------------------------------------------------

  private LiteralWire toWire(Literal literal) {
    return switch (literal) {
      case Literal.Text t -> new LiteralWire.TextWire(t.value());
      case Literal.Numeric n -> new LiteralWire.NumericWire(n.value());
      case Literal.Bool b -> new LiteralWire.BoolWire(b.value());
      case Literal.Date d -> new LiteralWire.DateWire(d.value());
      case Literal.Duration d -> new LiteralWire.DurationWire(d.value());
      case Literal.TextSet s -> new LiteralWire.TextSetWire(s.values());
      case Literal.None ignored -> new LiteralWire.NoneWire();
    };
  }

  private Literal toDomain(LiteralWire wire) {
    return switch (wire) {
      case LiteralWire.TextWire t -> Literal.text(t.value());
      case LiteralWire.NumericWire n -> Literal.number(n.value());
      case LiteralWire.BoolWire b -> Literal.bool(b.value());
      case LiteralWire.DateWire d -> Literal.date(d.value());
      case LiteralWire.DurationWire d -> Literal.duration(d.value());
      case LiteralWire.TextSetWire s -> Literal.textSet(s.values());
      case LiteralWire.NoneWire ignored -> Literal.none();
    };
  }

  // ---------------------------------------------------------------------
  // Formato de fio -- nunca carrega PolicyField, só o id como String.
  // ---------------------------------------------------------------------

  record RuleWire(
      String code,
      String name,
      ConditionWire when,
      int score,
      Severity severity,
      RiskRecommendation recommendation) {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = ConditionWire.AndWire.class, name = "and"),
    @JsonSubTypes.Type(value = ConditionWire.OrWire.class, name = "or"),
    @JsonSubTypes.Type(value = ConditionWire.NotWire.class, name = "not"),
    @JsonSubTypes.Type(value = ConditionWire.ComparisonWire.class, name = "cmp"),
    @JsonSubTypes.Type(value = ConditionWire.AnyOfWire.class, name = "anyOf")
  })
  sealed interface ConditionWire {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AndWire(List<ConditionWire> operands) implements ConditionWire {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrWire(List<ConditionWire> operands) implements ConditionWire {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NotWire(ConditionWire operand) implements ConditionWire {}

    /**
     * {@code fieldId} é a única informação sobre o campo que atravessa o JSON. Propriedade extra
     * como {@code type}/{@code exposure} numa linha gravada por fora (ou por um bug em outra
     * camada) é ignorada por {@code @JsonIgnoreProperties} -- não há como um valor forjado aqui
     * influenciar o {@link PolicyField} resolvido, porque a leitura nunca olha para eles.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ComparisonWire(String fieldId, Operator op, LiteralWire value)
        implements ConditionWire {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnyOfWire(String listFieldId, ConditionWire each) implements ConditionWire {}
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = LiteralWire.TextWire.class, name = "text"),
    @JsonSubTypes.Type(value = LiteralWire.NumericWire.class, name = "number"),
    @JsonSubTypes.Type(value = LiteralWire.BoolWire.class, name = "bool"),
    @JsonSubTypes.Type(value = LiteralWire.DateWire.class, name = "date"),
    @JsonSubTypes.Type(value = LiteralWire.DurationWire.class, name = "duration"),
    @JsonSubTypes.Type(value = LiteralWire.TextSetWire.class, name = "textSet"),
    @JsonSubTypes.Type(value = LiteralWire.NoneWire.class, name = "none")
  })
  sealed interface LiteralWire {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TextWire(String value) implements LiteralWire {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NumericWire(BigDecimal value) implements LiteralWire {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BoolWire(boolean value) implements LiteralWire {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DateWire(LocalDate value) implements LiteralWire {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DurationWire(Period value) implements LiteralWire {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TextSetWire(List<String> values) implements LiteralWire {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NoneWire() implements LiteralWire {}
  }
}
