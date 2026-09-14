package com.barrier.riskengine.riskpolicy.controller.dto;

import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicy;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.service.PolicyCompilationException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Converte entre o formato de fio da API ({@link PolicyConditionDto}/{@link PolicyLiteralDto}) e
 * a árvore de domínio ({@link Condition}/{@link Literal}) -- o mesmo par de direções que {@code
 * PolicyRuleJson} já resolve para a persistência, mas com uma diferença deliberada na leitura.
 *
 * <p><b>Id de campo desconhecido é erro do parceiro, não corrupção de dado.</b> {@code
 * PolicyRuleJson.resolveField} lança {@code UnknownPolicyFieldException} porque ali o JSON já foi
 * validado uma vez (na criação) e um id que não resolve mais é a base de dados divergindo do
 * catálogo. Aqui o JSON é a requisição HTTP, ainda não validada nenhuma vez -- um id que não
 * existe é o parceiro errando o nome do campo, e a resposta certa é {@link
 * PolicyCompilationException} (400, citando o id que não resolveu e apontando para {@code GET
 * /v1/policy-fields}), não uma exceção de integridade interna.
 */
@Component
public class PolicyDtoMapper {

  private final FieldCatalog catalog;

  public PolicyDtoMapper(FieldCatalog catalog) {
    this.catalog = catalog;
  }

  // ---------------------------------------------------------------------
  // Requisição -> domínio
  // ---------------------------------------------------------------------

  public List<PolicyRule> toDomain(List<PolicyRuleDto> rules) {
    return rules.stream().map(this::toDomain).toList();
  }

  private PolicyRule toDomain(PolicyRuleDto dto) {
    return new PolicyRule(
        dto.code(),
        dto.name(),
        toDomain(dto.when()),
        dto.score(),
        dto.severity(),
        dto.recommendation());
  }

  private Condition toDomain(PolicyConditionDto dto) {
    return switch (dto) {
      case PolicyConditionDto.And and ->
          new Condition.And(and.operands().stream().map(this::toDomain).toList());
      case PolicyConditionDto.Or or ->
          new Condition.Or(or.operands().stream().map(this::toDomain).toList());
      case PolicyConditionDto.Not not -> new Condition.Not(toDomain(not.operand()));
      case PolicyConditionDto.Comparison c ->
          new Condition.Comparison(resolveField(c.field()), c.op(), toDomain(c.value()));
      case PolicyConditionDto.AnyOf anyOf ->
          new Condition.AnyOf(resolveField(anyOf.listField()), toDomain(anyOf.each()));
    };
  }

  private Literal toDomain(PolicyLiteralDto dto) {
    return switch (dto) {
      case PolicyLiteralDto.Text t -> Literal.text(t.value());
      case PolicyLiteralDto.Number n -> Literal.number(n.value());
      case PolicyLiteralDto.Bool b -> Literal.bool(b.value());
      case PolicyLiteralDto.Date d -> Literal.date(d.value());
      case PolicyLiteralDto.Duration d -> Literal.duration(d.value());
      case PolicyLiteralDto.TextSet s -> Literal.textSet(s.values());
      case PolicyLiteralDto.None ignored -> Literal.none();
    };
  }

  private PolicyField resolveField(String fieldId) {
    return catalog
        .find(fieldId)
        .orElseThrow(
            () ->
                new PolicyCompilationException(
                    "campo '"
                        + fieldId
                        + "' nao existe no catalogo de politica (versao "
                        + catalog.version()
                        + ") -- consulte GET /v1/policy-fields"));
  }

  // ---------------------------------------------------------------------
  // Domínio -> resposta
  // ---------------------------------------------------------------------

  public PolicyResponse toResponse(RiskPolicy policy) {
    return new PolicyResponse(
        policy.version(),
        policy.domain(),
        policy.status().name(),
        policy.catalogVersion(),
        toDto(policy.rules()),
        policy.createdBy(),
        policy.createdAt(),
        policy.activatedBy(),
        policy.activatedAt(),
        policy.archivedAt());
  }

  private List<PolicyRuleDto> toDto(List<PolicyRule> rules) {
    return rules.stream().map(this::toDto).toList();
  }

  private PolicyRuleDto toDto(PolicyRule rule) {
    return new PolicyRuleDto(
        rule.code(),
        rule.name(),
        toDto(rule.when()),
        rule.score(),
        rule.severity(),
        rule.recommendation());
  }

  private PolicyConditionDto toDto(Condition condition) {
    return switch (condition) {
      case Condition.And and ->
          new PolicyConditionDto.And(and.operands().stream().map(this::toDto).toList());
      case Condition.Or or ->
          new PolicyConditionDto.Or(or.operands().stream().map(this::toDto).toList());
      case Condition.Not not -> new PolicyConditionDto.Not(toDto(not.operand()));
      case Condition.Comparison c ->
          new PolicyConditionDto.Comparison(c.field().id(), c.op(), toDto(c.value()));
      case Condition.AnyOf anyOf ->
          new PolicyConditionDto.AnyOf(anyOf.listField().id(), toDto(anyOf.each()));
    };
  }

  private PolicyLiteralDto toDto(Literal literal) {
    return switch (literal) {
      case Literal.Text t -> new PolicyLiteralDto.Text(t.value());
      case Literal.Numeric n -> new PolicyLiteralDto.Number(n.value());
      case Literal.Bool b -> new PolicyLiteralDto.Bool(b.value());
      case Literal.Date d -> new PolicyLiteralDto.Date(d.value());
      case Literal.Duration d -> new PolicyLiteralDto.Duration(d.value());
      case Literal.TextSet s -> new PolicyLiteralDto.TextSet(s.values());
      case Literal.None ignored -> new PolicyLiteralDto.None();
    };
  }

  // ---------------------------------------------------------------------
  // Catálogo -> resposta
  // ---------------------------------------------------------------------

  public PolicyFieldCatalogResponse toResponse(FieldCatalog fieldCatalog) {
    List<PolicyFieldResponse> fields =
        fieldCatalog.all().stream()
            .map(
                f ->
                    new PolicyFieldResponse(
                        f.id(), f.type().name(), f.exposure().name(), f.parentListId()))
            .toList();
    return new PolicyFieldCatalogResponse(fieldCatalog.version(), fields);
  }
}
