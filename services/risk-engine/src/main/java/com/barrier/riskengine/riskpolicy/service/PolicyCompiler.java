package com.barrier.riskengine.riskpolicy.service;

import com.barrier.riskengine.risk.registry.domain.RegulatoryRiskRules;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField;
import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyFieldType;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Compila as {@link PolicyRule} de uma política de parceiro, aplicando as quatro travas do piso
 * regulatório (desenho, §5.5). Política que viola qualquer uma não compila — e, portanto, não
 * pode ser ativada.
 *
 * <p><b>Trava 1 é a que sustenta o produto inteiro.</b> {@code ScoreAggregation} já soma scores e
 * reduz recomendações com {@code RiskRecommendation::strongest} — os dois são monotônicos. O
 * único jeito de uma regra custom afrouxar uma decisão do motor é um score negativo. Barrar isso
 * aqui é o que permite vender "regra de parceiro" para uma instituição regulada sem abrir uma
 * forma de relaxar o próprio controle de PLD-FT.
 *
 * <p>As outras três travas existem para que a trava 1 não seja contornável por outro caminho:
 * namespace ({@code CUSTOM_}) para não colidir com família do motor nem com {@link
 * RegulatoryRiskRules}; árvore só sobre campo do catálogo, com operador e literal válidos para o
 * tipo do campo, e campo de elemento só dentro do {@code AnyOf} da sua própria lista; teto de
 * profundidade e de número de nós para o custo de avaliação ficar limitado.
 */
@Component
public class PolicyCompiler {

  /** Profundidade máxima de uma árvore de condição (folha = 1). */
  public static final int MAX_DEPTH = 10;

  /** Número máximo de nós ({@code Condition}) em uma árvore. */
  public static final int MAX_NODES = 200;

  private static final String CUSTOM_PREFIX = "CUSTOM_";
  private static final Pattern CODE_PATTERN = Pattern.compile("^CUSTOM_[A-Z0-9_]+$");

  private final FieldCatalog catalog;

  public PolicyCompiler(FieldCatalog catalog) {
    this.catalog = catalog;
  }

  /**
   * Compila a lista inteira de regras de uma política, na ordem em que aparecem. Lança na
   * primeira regra que violar qualquer trava; devolve a mesma lista (já validada) quando todas
   * compilam.
   */
  public List<PolicyRule> compile(List<PolicyRule> rules) {
    Set<String> codigosVistos = new HashSet<>();
    for (PolicyRule rule : rules) {
      compileOne(rule, codigosVistos);
    }
    return List.copyOf(rules);
  }

  private void compileOne(PolicyRule rule, Set<String> codigosVistos) {
    checarScoreNaoNegativo(rule);
    checarNamespaceDoCodigo(rule, codigosVistos);
    checarArvore(rule);
    checarTamanhoDaArvore(rule);
  }

  // ---------------------------------------------------------------------
  // Trava 1: score >= 0
  // ---------------------------------------------------------------------

  private void checarScoreNaoNegativo(PolicyRule rule) {
    if (rule.score() < 0) {
      throw new PolicyCompilationException(
          "regra '"
              + rule.code()
              + "': score "
              + rule.score()
              + " invalido -- score de regra custom tem que ser >= 0 (trava 1: score negativo e"
              + " o unico jeito de uma regra de parceiro afrouxar a decisao do motor)");
    }
  }

  // ---------------------------------------------------------------------
  // Trava 2: namespace do codigo
  // ---------------------------------------------------------------------

  private void checarNamespaceDoCodigo(PolicyRule rule, Set<String> codigosVistos) {
    String code = rule.code();
    if (!CODE_PATTERN.matcher(code).matches()) {
      throw new PolicyCompilationException(
          "regra '"
              + code
              + "': codigo invalido -- tem que seguir o padrao CUSTOM_[A-Z0-9_]+ (trava 2:"
              + " codigo de regra custom vive no namespace CUSTOM_, para nao colidir com familia"
              + " do motor)");
    }
    if (RegulatoryRiskRules.isRegulatory(code)) {
      throw new PolicyCompilationException(
          "regra '"
              + code
              + "': codigo colide com familia de regra regulatoria protegida ("
              + RegulatoryRiskRules.codes()
              + ") -- trava 2: namespace");
    }
    String semPrefixo = code.substring(CUSTOM_PREFIX.length());
    if (RegulatoryRiskRules.isRegulatory(semPrefixo)) {
      throw new PolicyCompilationException(
          "regra '"
              + code
              + "': removido o prefixo CUSTOM_ o codigo vira '"
              + semPrefixo
              + "', que colide com familia de regra regulatoria protegida ("
              + RegulatoryRiskRules.codes()
              + ") -- trava 2: o prefixo nao pode virar bypass do namespace protegido");
    }
    if (!codigosVistos.add(code)) {
      throw new PolicyCompilationException(
          "regra '"
              + code
              + "': codigo repetido nesta versao da politica -- trava 2: codigo tem que ser"
              + " unico dentro da versao");
    }
  }

  // ---------------------------------------------------------------------
  // Trava 3: caminhada da arvore
  // ---------------------------------------------------------------------

  private void checarArvore(PolicyRule rule) {
    caminhar(rule.code(), rule.when(), null);
  }

  /**
   * @param listaEmEscopo id da lista do {@code AnyOf} mais interno em volta do nó atual, ou {@code
   *     null} fora de qualquer {@code AnyOf} — é contra este valor que um campo de elemento é
   *     validado.
   */
  private void caminhar(String code, Condition condition, String listaEmEscopo) {
    switch (condition) {
      case Condition.And and -> and.operands().forEach(op -> caminhar(code, op, listaEmEscopo));
      case Condition.Or or -> or.operands().forEach(op -> caminhar(code, op, listaEmEscopo));
      case Condition.Not not -> caminhar(code, not.operand(), listaEmEscopo);
      case Condition.Comparison comparison -> validarComparison(code, comparison, listaEmEscopo);
      case Condition.AnyOf anyOf -> validarAnyOf(code, anyOf);
    }
  }

  private void validarAnyOf(String code, Condition.AnyOf anyOf) {
    PolicyField listField = anyOf.listField();
    validarCampoConhecido(code, listField);
    if (listField.type() != PolicyFieldType.LIST) {
      throw new PolicyCompilationException(
          "regra '"
              + code
              + "': AnyOf referencia o campo '"
              + listField.id()
              + "' que nao e do tipo LIST -- trava 3: arvore invalida");
    }
    caminhar(code, anyOf.each(), listField.id());
  }

  private void validarComparison(
      String code, Condition.Comparison comparison, String listaEmEscopo) {
    PolicyField field = comparison.field();
    validarCampoConhecido(code, field);

    if (field.type() == PolicyFieldType.LIST) {
      throw new PolicyCompilationException(
          "regra '"
              + code
              + "': campo '"
              + field.id()
              + "' e do tipo LIST -- Comparison nunca compara uma lista inteira, use AnyOf"
              + " (trava 3: arvore invalida)");
    }

    validarEscopoDoCampoDeElemento(code, field, listaEmEscopo);

    Operator op = comparison.op();
    if (!op.supports(field.type())) {
      throw new PolicyCompilationException(
          "regra '"
              + code
              + "': campo '"
              + field.id()
              + "' ("
              + field.type()
              + ") nao aceita o operador "
              + op
              + " -- trava 3: arvore invalida");
    }

    Literal literal = comparison.value();
    if (!literalCompativel(field.type(), op, literal)) {
      throw new PolicyCompilationException(
          "regra '"
              + code
              + "': operador "
              + op
              + " sobre o campo '"
              + field.id()
              + "' recebeu literal do tipo "
              + tipoDoLiteral(literal)
              + ", incompativel com o operador -- trava 3: arvore invalida");
    }
  }

  /**
   * Campo de elemento (ex.: {@code company.partners[].foreign}) só pode aparecer dentro do {@code
   * AnyOf} da lista a que pertence — sem isso, não há elemento em escopo para resolvê-lo contra.
   */
  private void validarEscopoDoCampoDeElemento(
      String code, PolicyField field, String listaEmEscopo) {
    if (!field.isElementField()) {
      return;
    }
    if (listaEmEscopo == null) {
      throw new PolicyCompilationException(
          "regra '"
              + code
              + "': campo de elemento '"
              + field.id()
              + "' aparece fora de qualquer AnyOf -- so pode ser usado dentro do AnyOf da sua"
              + " propria lista (trava 3: arvore invalida)");
    }
    if (!field.parentListId().equals(listaEmEscopo)) {
      throw new PolicyCompilationException(
          "regra '"
              + code
              + "': campo de elemento '"
              + field.id()
              + "' pertence a lista '"
              + field.parentListId()
              + "', mas aparece dentro do AnyOf de '"
              + listaEmEscopo
              + "' -- trava 3: arvore invalida");
    }
  }

  private void validarCampoConhecido(String code, PolicyField field) {
    if (catalog.find(field.id()).isEmpty()) {
      throw new PolicyCompilationException(
          "regra '"
              + code
              + "': campo '"
              + field.id()
              + "' nao existe no catalogo de politica (versao "
              + catalog.version()
              + ") -- trava 3: arvore invalida");
    }
  }

  /**
   * Tipo de literal exigido por cada operador. Para {@code EQ}/{@code NEQ} o operador aceita
   * vários tipos de campo, então quem decide o literal certo é o tipo do campo, não o operador.
   */
  private boolean literalCompativel(PolicyFieldType fieldType, Operator op, Literal literal) {
    return switch (op) {
      case IS_NULL, IS_NOT_NULL -> literal instanceof Literal.None;
      case IN, NOT_IN -> literal instanceof Literal.TextSet;
      case STARTS_WITH -> literal instanceof Literal.Text;
      case LT, LTE, GT, GTE -> literal instanceof Literal.Numeric;
      case BEFORE, AFTER -> literal instanceof Literal.Date;
      case OLDER_THAN, WITHIN_LAST -> literal instanceof Literal.Duration;
      case EQ, NEQ -> literalCompativelComTipoDoCampo(fieldType, literal);
    };
  }

  private boolean literalCompativelComTipoDoCampo(PolicyFieldType fieldType, Literal literal) {
    return switch (fieldType) {
      case STRING, ENUM -> literal instanceof Literal.Text;
      case NUMBER -> literal instanceof Literal.Numeric;
      case BOOLEAN -> literal instanceof Literal.Bool;
      case DATE -> literal instanceof Literal.Date;
      case LIST -> false;
    };
  }

  private String tipoDoLiteral(Literal literal) {
    return switch (literal) {
      case Literal.Text ignored -> "TEXT";
      case Literal.Numeric ignored -> "NUMERIC";
      case Literal.Bool ignored -> "BOOL";
      case Literal.Date ignored -> "DATE";
      case Literal.Duration ignored -> "DURATION";
      case Literal.TextSet ignored -> "TEXT_SET";
      case Literal.None ignored -> "NONE";
    };
  }

  // ---------------------------------------------------------------------
  // Trava 4: teto de profundidade e de numero de nos
  // ---------------------------------------------------------------------

  private void checarTamanhoDaArvore(PolicyRule rule) {
    int profundidade = profundidade(rule.when());
    if (profundidade > MAX_DEPTH) {
      throw new PolicyCompilationException(
          "regra '"
              + rule.code()
              + "': arvore com profundidade "
              + profundidade
              + ", acima do teto "
              + MAX_DEPTH
              + " -- trava 4: profundidade e numero de nos");
    }
    int nos = contarNos(rule.when());
    if (nos > MAX_NODES) {
      throw new PolicyCompilationException(
          "regra '"
              + rule.code()
              + "': arvore com "
              + nos
              + " nos, acima do teto "
              + MAX_NODES
              + " -- trava 4: profundidade e numero de nos");
    }
  }

  private int profundidade(Condition condition) {
    return switch (condition) {
      case Condition.And and -> 1 + maiorProfundidade(and.operands());
      case Condition.Or or -> 1 + maiorProfundidade(or.operands());
      case Condition.Not not -> 1 + profundidade(not.operand());
      case Condition.Comparison ignored -> 1;
      case Condition.AnyOf anyOf -> 1 + profundidade(anyOf.each());
    };
  }

  private int maiorProfundidade(List<Condition> operands) {
    return operands.stream().mapToInt(this::profundidade).max().orElse(0);
  }

  private int contarNos(Condition condition) {
    return switch (condition) {
      case Condition.And and -> 1 + somaDeNos(and.operands());
      case Condition.Or or -> 1 + somaDeNos(or.operands());
      case Condition.Not not -> 1 + contarNos(not.operand());
      case Condition.Comparison ignored -> 1;
      case Condition.AnyOf anyOf -> 1 + contarNos(anyOf.each());
    };
  }

  private int somaDeNos(List<Condition> operands) {
    return operands.stream().mapToInt(this::contarNos).sum();
  }
}
