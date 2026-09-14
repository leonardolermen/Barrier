package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest {

  private static final Instant AGORA = Instant.parse("2026-09-01T00:00:00Z");
  private final ConditionEvaluator evaluator = new ConditionEvaluator();

  private RiskContext comEmpresa(LocalDate abertura, CompanyProfile.Partner... socios) {
    CompanyProfile empresa = new CompanyProfile(abertura, "6499-9/99", "desc", List.of(socios));
    return new RiskContext("a-1", "t-1", null, null, empresa, null, null, AGORA);
  }

  private Condition comparacao(String campo, Operator op, Literal valor) {
    return new Condition.Comparison(FieldCatalog.V1.find(campo).orElseThrow(), op, valor);
  }

  @Test
  void empresa_aberta_dentro_da_janela_casa() {
    Condition c =
        comparacao(
            "company.openingDate", Operator.WITHIN_LAST, Literal.duration(Period.ofMonths(6)));

    assertThat(evaluator.evaluate(c, comEmpresa(LocalDate.of(2026, 7, 1))).matched()).isTrue();
    assertThat(evaluator.evaluate(c, comEmpresa(LocalDate.of(2020, 1, 1))).matched()).isFalse();
  }

  @Test
  void any_of_acha_socio_estrangeiro_pj() {
    Condition each =
        new Condition.And(
            List.of(
                comparacao("company.partners[].foreign", Operator.EQ, Literal.bool(true)),
                comparacao("company.partners[].legalEntity", Operator.EQ, Literal.bool(true))));
    Condition c =
        new Condition.AnyOf(FieldCatalog.V1.find("company.partners").orElseThrow(), each);

    RiskContext comHolding =
        comEmpresa(
            LocalDate.of(2020, 1, 1), new CompanyProfile.Partner("ACME BV", true, true, "Sócio"));
    RiskContext soPessoaFisica =
        comEmpresa(
            LocalDate.of(2020, 1, 1), new CompanyProfile.Partner("Fulano", false, false, "Sócio"));

    assertThat(evaluator.evaluate(c, comHolding).matched()).isTrue();
    assertThat(evaluator.evaluate(c, soPessoaFisica).matched()).isFalse();
  }

  @Test
  void any_of_sobre_lista_vazia_e_falso_e_nao_estoura() {
    Condition c =
        new Condition.AnyOf(
            FieldCatalog.V1.find("company.partners").orElseThrow(),
            comparacao("company.partners[].foreign", Operator.EQ, Literal.bool(true)));

    assertThat(evaluator.evaluate(c, comEmpresa(LocalDate.of(2020, 1, 1))).matched()).isFalse();
  }

  @Test
  void not_inverte() {
    Condition dentro =
        comparacao(
            "company.openingDate", Operator.WITHIN_LAST, Literal.duration(Period.ofMonths(6)));

    assertThat(
            evaluator
                .evaluate(new Condition.Not(dentro), comEmpresa(LocalDate.of(2020, 1, 1)))
                .matched())
        .isTrue();
  }

  @Test
  void campo_ausente_nao_casa_e_nao_estoura() {
    RiskContext semEmpresa = new RiskContext("a-1", "t-1", null, null, null, null, null, AGORA);
    Condition c =
        comparacao(
            "company.openingDate", Operator.WITHIN_LAST, Literal.duration(Period.ofMonths(6)));

    assertThat(evaluator.evaluate(c, semEmpresa).matched()).isFalse();
  }

  @Test
  void evidencia_traz_o_valor_de_campo_publico() {
    Condition c =
        comparacao(
            "company.openingDate", Operator.WITHIN_LAST, Literal.duration(Period.ofMonths(6)));

    assertThat(evaluator.evaluate(c, comEmpresa(LocalDate.of(2026, 7, 1))).evidences())
        .anySatisfy(e -> assertThat(e).contains("company.openingDate").contains("2026-07-01"));
  }

  @Test
  void evidencia_omite_valor_de_campo_sensivel() {
    // profile.birthDate é OUTCOME_ONLY: a evidência cita o campo e o operador, nunca a data.
    SubjectProfile perfil = SubjectProfileFixtures.comNascimento(LocalDate.of(1990, 5, 20));
    RiskContext ctx = new RiskContext("a-1", "t-1", null, null, null, perfil, null, AGORA);
    Condition c =
        comparacao("profile.birthDate", Operator.OLDER_THAN, Literal.duration(Period.ofYears(18)));

    var resultado = evaluator.evaluate(c, ctx);

    assertThat(resultado.matched()).isTrue();
    assertThat(resultado.evidences()).isNotEmpty();
    assertThat(resultado.evidences()).noneSatisfy(e -> assertThat(e).contains("1990"));
  }

  @Test
  void so_comparacao_verdadeira_entra_na_evidencia() {
    Condition c =
        new Condition.Or(
            List.of(
                comparacao(
                    "company.openingDate",
                    Operator.WITHIN_LAST,
                    Literal.duration(Period.ofMonths(6))),
                comparacao("company.cnaeCode", Operator.EQ, Literal.text("9999-9/99"))));

    var resultado = evaluator.evaluate(c, comEmpresa(LocalDate.of(2026, 7, 1)));

    assertThat(resultado.matched()).isTrue();
    assertThat(resultado.evidences()).noneSatisfy(e -> assertThat(e).contains("cnaeCode"));
  }
}
