package com.barrier.riskengine.riskpolicy.service;

import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.riskpolicy.domain.catalog.EvidenceExposure;
import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField;
import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyFieldType;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Caminha a {@link Condition} de uma regra de política e devolve casou/não-casou junto da
 * evidência legível que explica o motivo.
 *
 * <p>Sem estado: {@link #evaluate} pode ser chamado de qualquer thread, para qualquer política. O
 * {@code switch} sobre a interface selada {@link Condition} é exaustivo — nó novo na árvore
 * não compila sem um caso aqui.
 */
@Component
public class ConditionEvaluator {

  private static final ClauseEvaluation NAO_CASOU = new ClauseEvaluation(false, List.of());

  public ClauseEvaluation evaluate(Condition condition, RiskContext context) {
    return eval(condition, context, null);
  }

  private ClauseEvaluation eval(Condition condition, RiskContext context, Object elementoEmEscopo) {
    return switch (condition) {
      case Condition.And and -> avaliarAnd(and.operands(), context, elementoEmEscopo);
      case Condition.Or or -> avaliarOr(or.operands(), context, elementoEmEscopo);
      case Condition.Not not -> avaliarNot(not.operand(), context, elementoEmEscopo);
      case Condition.Comparison comparison ->
          avaliarComparison(comparison, context, elementoEmEscopo);
      case Condition.AnyOf anyOf -> avaliarAnyOf(anyOf, context);
    };
  }

  /**
   * Avalia todos os operandos, sem curto-circuito: a evidência é o produto, e parar no
   * primeiro falso esconderia o motivo dos demais. A árvore tem teto de tamanho, então o custo
   * é limitado.
   */
  private ClauseEvaluation avaliarAnd(
      List<Condition> operands, RiskContext context, Object elementoEmEscopo) {
    boolean matched = true;
    List<String> evidencias = new ArrayList<>();
    for (Condition operando : operands) {
      ClauseEvaluation resultado = eval(operando, context, elementoEmEscopo);
      matched = matched && resultado.matched();
      evidencias.addAll(resultado.evidences());
    }
    return new ClauseEvaluation(matched, evidencias);
  }

  private ClauseEvaluation avaliarOr(
      List<Condition> operands, RiskContext context, Object elementoEmEscopo) {
    boolean matched = false;
    List<String> evidencias = new ArrayList<>();
    for (Condition operando : operands) {
      ClauseEvaluation resultado = eval(operando, context, elementoEmEscopo);
      matched = matched || resultado.matched();
      evidencias.addAll(resultado.evidences());
    }
    return new ClauseEvaluation(matched, evidencias);
  }

  /** Inverte {@code matched} e descarta a evidência do operando: negada, ela diria o oposto. */
  private ClauseEvaluation avaliarNot(
      Condition operand, RiskContext context, Object elementoEmEscopo) {
    ClauseEvaluation resultado = eval(operand, context, elementoEmEscopo);
    return new ClauseEvaluation(!resultado.matched(), List.of());
  }

  /**
   * Resolve a lista (vazia quando ausente) e avalia {@code each} contra cada elemento. Devolve as
   * evidências só do primeiro elemento que casou — os demais não contribuem para o motivo.
   */
  private ClauseEvaluation avaliarAnyOf(Condition.AnyOf anyOf, RiskContext context) {
    Object valor = anyOf.listField().resolve(context);
    List<?> elementos = valor == null ? List.of() : (List<?>) valor;
    for (Object elemento : elementos) {
      ClauseEvaluation resultado = eval(anyOf.each(), context, elemento);
      if (resultado.matched()) {
        return resultado;
      }
    }
    return NAO_CASOU;
  }

  /**
   * Comparação sobre campo {@code LIST} é inválida (a compilação da Task 4 recusa); aqui vira
   * falso em vez de estourar, no mesmo espírito de campo ausente.
   */
  private ClauseEvaluation avaliarComparison(
      Condition.Comparison comparison, RiskContext context, Object elementoEmEscopo) {
    PolicyField field = comparison.field();
    if (field.type() == PolicyFieldType.LIST) {
      return NAO_CASOU;
    }
    Object raiz = field.isElementField() ? elementoEmEscopo : context;
    Object valor = field.resolve(raiz);
    Operator op = comparison.op();
    Literal literal = comparison.value();

    boolean matched =
        valor == null
            ? op == Operator.IS_NULL
            : comparaComFalhaFechada(valor, op, literal, context);
    if (!matched) {
      return NAO_CASOU;
    }
    return new ClauseEvaluation(true, List.of(evidencia(field, op, literal, valor)));
  }

  /**
   * {@code compara} assume que Task 4 já recusou par campo/operador/literal incompatível — hoje
   * nada garante isso em runtime (o compilador ainda não existe), e um tipo incompatível vira
   * {@code ClassCastException}/{@code NumberFormatException} em vez de falso. Essas exceções
   * carregam o valor cru na mensagem (ex.: {@code "For input string: \"<valor>\""}), o que para um
   * campo {@code OUTCOME_ONLY} vazaria por log exatamente o que a exposição existe para reter.
   *
   * <p>Valor nulo já devolve falso, campo {@code LIST} já devolve falso — este é o mesmo
   * comportamento para o terceiro jeito de a comparação não fazer sentido. Sem log: nem a exceção
   * nem o valor, porque logar a exceção é logar o valor.
   */
  private boolean comparaComFalhaFechada(
      Object valor, Operator op, Literal literal, RiskContext context) {
    try {
      return compara(valor, op, literal, context);
    } catch (RuntimeException incompativel) {
      return false;
    }
  }

  private boolean compara(Object valor, Operator op, Literal literal, RiskContext context) {
    return switch (op) {
      case IS_NULL -> false;
      case IS_NOT_NULL -> true;
      case EQ -> igual(valor, literal);
      case NEQ -> !igual(valor, literal);
      case IN -> pertenceAoConjunto(valor, (Literal.TextSet) literal);
      case NOT_IN -> !pertenceAoConjunto(valor, (Literal.TextSet) literal);
      case STARTS_WITH -> String.valueOf(valor).startsWith(((Literal.Text) literal).value());
      case LT -> comoBigDecimal(valor).compareTo(((Literal.Numeric) literal).value()) < 0;
      case LTE -> comoBigDecimal(valor).compareTo(((Literal.Numeric) literal).value()) <= 0;
      case GT -> comoBigDecimal(valor).compareTo(((Literal.Numeric) literal).value()) > 0;
      case GTE -> comoBigDecimal(valor).compareTo(((Literal.Numeric) literal).value()) >= 0;
      case BEFORE -> ((LocalDate) valor).isBefore(((Literal.Date) literal).value());
      case AFTER -> ((LocalDate) valor).isAfter(((Literal.Date) literal).value());
      case OLDER_THAN ->
          maisVelhoQue((LocalDate) valor, ((Literal.Duration) literal).value(), context);
      case WITHIN_LAST ->
          dentroDosUltimos((LocalDate) valor, ((Literal.Duration) literal).value(), context);
    };
  }

  /**
   * {@code IN}/{@code NOT_IN} sobre campo {@code NUMBER} tem que comparar como {@link BigDecimal}
   * (via {@code compareTo}), não como string -- {@code String.valueOf} de {@code 100} e {@code
   * 100.00} divergem por escala decimal, e {@code EQ} sobre o mesmo campo já usa {@code
   * compareTo} (mesmo raciocínio de {@link #igual}). Sem isto, dois operadores do mesmo tipo de
   * campo discordavam sobre o que é o mesmo número -- e a discordância era silenciosa: a regra
   * compilava e nunca disparava. Sobre {@code STRING}/{@code ENUM} o membro do conjunto é texto, e
   * a comparação continua sendo por igualdade de string.
   */
  private boolean pertenceAoConjunto(Object valor, Literal.TextSet literal) {
    if (valor instanceof BigDecimal numero) {
      return literal.values().stream()
          .anyMatch(membro -> comoBigDecimal(membro).compareTo(numero) == 0);
    }
    return literal.values().contains(String.valueOf(valor));
  }

  private boolean igual(Object valor, Literal literal) {
    return switch (literal) {
      case Literal.Text t -> t.value().equals(String.valueOf(valor));
      case Literal.Numeric n -> comoBigDecimal(valor).compareTo(n.value()) == 0;
      case Literal.Bool b -> Objects.equals(valor, b.value());
      case Literal.Date d -> Objects.equals(valor, d.value());
      case Literal.Duration ignored -> false;
      case Literal.TextSet ignored -> false;
      case Literal.None ignored -> false;
    };
  }

  private BigDecimal comoBigDecimal(Object valor) {
    return valor instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(valor));
  }

  /** {@code referenceInstant} convertido para {@link LocalDate} em UTC — nunca o relógio real. */
  private LocalDate dataDeReferencia(RiskContext context) {
    return LocalDate.ofInstant(context.referenceInstant(), ZoneOffset.UTC);
  }

  private boolean maisVelhoQue(LocalDate valor, Period duracao, RiskContext context) {
    return !valor.isAfter(dataDeReferencia(context).minus(duracao));
  }

  private boolean dentroDosUltimos(LocalDate valor, Period duracao, RiskContext context) {
    return !valor.isBefore(dataDeReferencia(context).minus(duracao));
  }

  /** Evidência só para {@code Comparison} que avaliou verdadeiro — chamada só a partir daí. */
  private String evidencia(PolicyField field, Operator op, Literal literal, Object valor) {
    return field.exposure() == EvidenceExposure.OUTCOME_ONLY
        ? evidenciaSemValor(field, op, literal)
        : evidenciaComValor(field, op, literal, valor);
  }

  /**
   * Sem parâmetro de valor: a estrutura torna o vazamento impossível, em vez de só evitá-lo —
   * não há como este método imprimir o que não recebeu.
   */
  private String evidenciaSemValor(PolicyField field, Operator op, Literal literal) {
    return field.id() + " " + rotuloOperador(op, literal) + " (valor omitido)";
  }

  private String evidenciaComValor(PolicyField field, Operator op, Literal literal, Object valor) {
    String alvo = valor == null ? field.id() : field.id() + "=" + valor;
    return alvo + " " + rotuloOperador(op, literal);
  }

  private String rotuloOperador(Operator op, Literal literal) {
    String texto = literalTexto(literal);
    return texto.isEmpty() ? op.name() : op.name() + " " + texto;
  }

  private String literalTexto(Literal literal) {
    return switch (literal) {
      case Literal.Text t -> t.value();
      case Literal.Numeric n -> n.value().toPlainString();
      case Literal.Bool b -> String.valueOf(b.value());
      case Literal.Date d -> d.value().toString();
      case Literal.Duration d -> d.value().toString();
      case Literal.TextSet s -> String.join(",", s.values());
      case Literal.None ignored -> "";
    };
  }

  /** Resultado da avaliação de uma cláusula: se casou, e a evidência legível de por quê. */
  public record ClauseEvaluation(boolean matched, List<String> evidences) {
    public ClauseEvaluation {
      evidences = List.copyOf(evidences);
    }
  }
}
