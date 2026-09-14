package com.barrier.riskengine.riskpolicy.domain.tree;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;

/**
 * Valor literal do lado direito de uma {@link Condition.Comparison}.
 *
 * <p>Selado, com fábricas estáticas em vez de construção direta dos records — quem monta uma
 * condição escreve {@code Literal.date(...)}, nunca {@code new Literal.Date(...)}.
 */
public sealed interface Literal {

  record Text(String value) implements Literal {}

  record Numeric(BigDecimal value) implements Literal {}

  record Bool(boolean value) implements Literal {}

  record Date(LocalDate value) implements Literal {}

  record Duration(Period value) implements Literal {}

  record TextSet(List<String> values) implements Literal {
    public TextSet {
      values = List.copyOf(values);
    }
  }

  /** Para {@code IS_NULL} e {@code IS_NOT_NULL}, que não comparam contra nada. */
  record None() implements Literal {}

  static Literal text(String value) {
    return new Text(value);
  }

  static Literal number(BigDecimal value) {
    return new Numeric(value);
  }

  static Literal bool(boolean value) {
    return new Bool(value);
  }

  static Literal date(LocalDate value) {
    return new Date(value);
  }

  static Literal duration(Period value) {
    return new Duration(value);
  }

  static Literal textSet(List<String> values) {
    return new TextSet(values);
  }

  static Literal none() {
    return new None();
  }
}
