package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code @TestInstance(PER_CLASS)}: {@link #operatorCases()} usado pelo {@code @MethodSource}
 * precisa enxergar os helpers de instância ({@link #comEmpresa}, {@link #comparacao}) sem
 * duplicá-los como estáticos.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
  void not_anyof_funciona_como_noneof() {
    // NoneOf não existe como nó próprio -- é Not(AnyOf(...)). "Nenhum sócio estrangeiro."
    Condition algumEstrangeiro =
        new Condition.AnyOf(
            FieldCatalog.V1.find("company.partners").orElseThrow(),
            comparacao("company.partners[].foreign", Operator.EQ, Literal.bool(true)));
    Condition nenhumEstrangeiro = new Condition.Not(algumEstrangeiro);

    RiskContext comHolding =
        comEmpresa(
            LocalDate.of(2020, 1, 1), new CompanyProfile.Partner("ACME BV", true, true, "Sócio"));
    RiskContext soPessoaFisica =
        comEmpresa(
            LocalDate.of(2020, 1, 1), new CompanyProfile.Partner("Fulano", false, false, "Sócio"));

    assertThat(evaluator.evaluate(nenhumEstrangeiro, comHolding).matched()).isFalse();
    assertThat(evaluator.evaluate(nenhumEstrangeiro, soPessoaFisica).matched()).isTrue();
  }

  @Test
  void comparison_sobre_campo_lista_e_falso_e_nao_estoura() {
    // Comparison sobre campo LIST é inválida (Task 4 recusa na compilação); aqui vira falso, no
    // mesmo espírito de campo ausente. Todos os outros testes de lista passam por AnyOf -- este é
    // o único que exercita o Comparison direto sobre um campo LIST.
    Condition c = comparacao("company.partners", Operator.EQ, Literal.none());

    assertThat(evaluator.evaluate(c, comEmpresa(LocalDate.of(2020, 1, 1))).matched()).isFalse();
  }

  @Test
  void comparacao_com_tipo_incompativel_falha_fechada_em_vez_de_estourar() {
    // A compilação da Task 4 ainda não existe para recusar isto antes da avaliação -- hoje nada
    // impede montar uma Comparison com par campo/operador/literal incompatível. Sem falha
    // fechada, LT sobre um campo STRING estouraria NumberFormatException com o valor cru na
    // mensagem (aqui "6499-9/99", mas para um campo OUTCOME_ONLY seria o dado que a exposição
    // existe para reter).
    Condition c =
        comparacao("company.cnaeCode", Operator.LT, Literal.number(new BigDecimal("100")));

    assertThat(evaluator.evaluate(c, comEmpresa(LocalDate.of(2020, 1, 1))).matched()).isFalse();
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
  void or_nao_da_curto_circuito_evidencia_dos_verdadeiros_aparece_mesmo_apos_um_falso() {
    // Um par (verdadeiro, falso) não prova ausência de curto-circuito: como só comparação
    // verdadeira gera evidência, esse par passaria igual com ou sem curto-circuito. Aqui há dois
    // operandos verdadeiros (1º e 3º) intercalados por um falso (2º); se o Or parasse no primeiro
    // falso, a evidência do terceiro -- que só existe depois dele na lista -- nunca apareceria.
    Condition c =
        new Condition.Or(
            List.of(
                comparacao(
                    "company.openingDate",
                    Operator.WITHIN_LAST,
                    Literal.duration(Period.ofMonths(6))),
                comparacao("company.cnaeCode", Operator.EQ, Literal.text("9999-9/99")),
                comparacao("company.cnaeCode", Operator.EQ, Literal.text("6499-9/99"))));

    var resultado = evaluator.evaluate(c, comEmpresa(LocalDate.of(2026, 7, 1)));

    assertThat(resultado.matched()).isTrue();
    assertThat(resultado.evidences()).hasSize(2);
    assertThat(resultado.evidences())
        .anySatisfy(e -> assertThat(e).contains("company.openingDate"))
        .anySatisfy(e -> assertThat(e).contains("company.cnaeCode").contains("6499-9/99"));
    assertThat(resultado.evidences()).noneSatisfy(e -> assertThat(e).contains("9999-9/99"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("operatorCases")
  void operador_segue_a_tabela_do_5_2(OperatorCase caso) {
    assertThat(evaluator.evaluate(caso.condition(), caso.context()).matched())
        .isEqualTo(caso.expectedMatched());
  }

  /**
   * Um caso que casa e um que não casa por operador da §5.2 — par deliberado: um operador que
   * sempre devolvesse {@code true} passaria num teste que só cobre o caso positivo, e aqui o
   * avaliador decide se uma regra de risco dispara ou fica muda sem log nenhum dizendo por quê.
   * Cobre também os tipos que os 8 testes originais não exercitavam: NUMBER (com escala decimal
   * diferente, {@code 100} contra {@code 100.00}) e ENUM com IN/NOT_IN. NUMBER com IN/NOT_IN é
   * achado da revisão final: o par casa/não-casa aqui usa a mesma escala decimal diferente de
   * EQ/LTE/GTE de propósito -- {@code IN}/{@code NOT_IN} comparavam o membro do conjunto como
   * string, e "100.00" (o valor guardado) nunca é igual a "100" (o que o parceiro escreve) por
   * igualdade textual, mesmo sendo o mesmo número.
   */
  private Stream<OperatorCase> operatorCases() {
    RiskContext comEmpresaAberta2026 = comEmpresa(LocalDate.of(2026, 7, 1));
    RiskContext comEmpresaAberta2020 = comEmpresa(LocalDate.of(2020, 1, 1));
    RiskContext comEmpresaAberta2010 = comEmpresa(LocalDate.of(2010, 1, 1));
    RiskContext comEmpresaAberta2000 = comEmpresa(LocalDate.of(2000, 1, 1));
    RiskContext semEmpresa = new RiskContext("a-1", "t-1", null, null, null, null, null, AGORA);

    RiskContext identidadeVerificada =
        new RiskContext(
            "a-1",
            "t-1",
            IdentityCheck.create("a-1", IdentityStatus.VERIFIED, "stub", "ok"),
            null,
            null,
            null,
            null,
            AGORA);
    RiskContext identidadeNaoEncontrada =
        new RiskContext(
            "a-1",
            "t-1",
            IdentityCheck.create("a-1", IdentityStatus.NOT_FOUND, "stub", "nao encontrado"),
            null,
            null,
            null,
            null,
            AGORA);

    RiskContext comOcupacaoMedico =
        new RiskContext(
            "a-1",
            "t-1",
            null,
            null,
            null,
            SubjectProfileFixtures.comOcupacao("Médico"),
            null,
            AGORA);

    RiskContext comCapitalCem =
        new RiskContext(
            "a-1",
            "t-1",
            null,
            null,
            null,
            SubjectProfileFixtures.comCapitalSocial(new BigDecimal("100.00")),
            null,
            AGORA);

    return Stream.of(
        new OperatorCase(
            "EQ casa (string)",
            comparacao("company.cnaeCode", Operator.EQ, Literal.text("6499-9/99")),
            comEmpresaAberta2026,
            true),
        new OperatorCase(
            "EQ nao casa (string)",
            comparacao("company.cnaeCode", Operator.EQ, Literal.text("0000-0/00")),
            comEmpresaAberta2026,
            false),
        new OperatorCase(
            "EQ casa (number, escala decimal diferente: 100 == 100.00)",
            comparacao("profile.shareCapital", Operator.EQ, Literal.number(new BigDecimal("100"))),
            comCapitalCem,
            true),
        new OperatorCase(
            "EQ nao casa (number)",
            comparacao("profile.shareCapital", Operator.EQ, Literal.number(new BigDecimal("250"))),
            comCapitalCem,
            false),
        new OperatorCase(
            "NEQ casa",
            comparacao("company.cnaeCode", Operator.NEQ, Literal.text("0000-0/00")),
            comEmpresaAberta2026,
            true),
        new OperatorCase(
            "NEQ nao casa",
            comparacao("company.cnaeCode", Operator.NEQ, Literal.text("6499-9/99")),
            comEmpresaAberta2026,
            false),
        new OperatorCase(
            "IS_NULL casa (campo ausente)",
            comparacao("company.openingDate", Operator.IS_NULL, Literal.none()),
            semEmpresa,
            true),
        new OperatorCase(
            "IS_NULL nao casa (campo presente)",
            comparacao("company.openingDate", Operator.IS_NULL, Literal.none()),
            comEmpresaAberta2026,
            false),
        new OperatorCase(
            "IS_NOT_NULL casa (campo presente)",
            comparacao("company.openingDate", Operator.IS_NOT_NULL, Literal.none()),
            comEmpresaAberta2026,
            true),
        new OperatorCase(
            "IS_NOT_NULL nao casa (campo ausente)",
            comparacao("company.openingDate", Operator.IS_NOT_NULL, Literal.none()),
            semEmpresa,
            false),
        new OperatorCase(
            "IN casa (enum)",
            comparacao(
                "identity.status", Operator.IN, Literal.textSet(List.of("VERIFIED", "MISMATCH"))),
            identidadeVerificada,
            true),
        new OperatorCase(
            "IN nao casa (enum)",
            comparacao(
                "identity.status", Operator.IN, Literal.textSet(List.of("VERIFIED", "MISMATCH"))),
            identidadeNaoEncontrada,
            false),
        new OperatorCase(
            "NOT_IN casa (enum)",
            comparacao(
                "identity.status",
                Operator.NOT_IN,
                Literal.textSet(List.of("VERIFIED", "MISMATCH"))),
            identidadeNaoEncontrada,
            true),
        new OperatorCase(
            "NOT_IN nao casa (enum)",
            comparacao(
                "identity.status",
                Operator.NOT_IN,
                Literal.textSet(List.of("VERIFIED", "MISMATCH"))),
            identidadeVerificada,
            false),
        new OperatorCase(
            "IN casa (number, escala decimal diferente: 100 == 100.00)",
            comparacao(
                "profile.shareCapital", Operator.IN, Literal.textSet(List.of("100", "999"))),
            comCapitalCem,
            true),
        new OperatorCase(
            "IN nao casa (number)",
            comparacao(
                "profile.shareCapital", Operator.IN, Literal.textSet(List.of("250", "999"))),
            comCapitalCem,
            false),
        new OperatorCase(
            "NOT_IN casa (number)",
            comparacao(
                "profile.shareCapital", Operator.NOT_IN, Literal.textSet(List.of("250", "999"))),
            comCapitalCem,
            true),
        new OperatorCase(
            "NOT_IN nao casa (number, escala decimal diferente: 100 == 100.00)",
            comparacao(
                "profile.shareCapital", Operator.NOT_IN, Literal.textSet(List.of("100", "999"))),
            comCapitalCem,
            false),
        new OperatorCase(
            "STARTS_WITH casa (acento preservado)",
            comparacao("profile.occupation", Operator.STARTS_WITH, Literal.text("Méd")),
            comOcupacaoMedico,
            true),
        new OperatorCase(
            "STARTS_WITH nao casa (sensivel a acento e caixa)",
            comparacao("profile.occupation", Operator.STARTS_WITH, Literal.text("med")),
            comOcupacaoMedico,
            false),
        new OperatorCase(
            "LT casa",
            comparacao("profile.shareCapital", Operator.LT, Literal.number(new BigDecimal("200"))),
            comCapitalCem,
            true),
        new OperatorCase(
            "LT nao casa",
            comparacao("profile.shareCapital", Operator.LT, Literal.number(new BigDecimal("50"))),
            comCapitalCem,
            false),
        new OperatorCase(
            "LTE casa (igual, escala diferente)",
            comparacao(
                "profile.shareCapital", Operator.LTE, Literal.number(new BigDecimal("100"))),
            comCapitalCem,
            true),
        new OperatorCase(
            "LTE nao casa",
            comparacao("profile.shareCapital", Operator.LTE, Literal.number(new BigDecimal("50"))),
            comCapitalCem,
            false),
        new OperatorCase(
            "GT casa",
            comparacao("profile.shareCapital", Operator.GT, Literal.number(new BigDecimal("50"))),
            comCapitalCem,
            true),
        new OperatorCase(
            "GT nao casa",
            comparacao("profile.shareCapital", Operator.GT, Literal.number(new BigDecimal("200"))),
            comCapitalCem,
            false),
        new OperatorCase(
            "GTE casa (igual, escala diferente)",
            comparacao(
                "profile.shareCapital", Operator.GTE, Literal.number(new BigDecimal("100"))),
            comCapitalCem,
            true),
        new OperatorCase(
            "GTE nao casa",
            comparacao("profile.shareCapital", Operator.GTE, Literal.number(new BigDecimal("200"))),
            comCapitalCem,
            false),
        new OperatorCase(
            "BEFORE casa",
            comparacao(
                "company.openingDate", Operator.BEFORE, Literal.date(LocalDate.of(2026, 1, 1))),
            comEmpresaAberta2020,
            true),
        new OperatorCase(
            "BEFORE nao casa",
            comparacao(
                "company.openingDate", Operator.BEFORE, Literal.date(LocalDate.of(2026, 1, 1))),
            comEmpresaAberta2026,
            false),
        new OperatorCase(
            "AFTER casa",
            comparacao(
                "company.openingDate", Operator.AFTER, Literal.date(LocalDate.of(2020, 1, 1))),
            comEmpresaAberta2026,
            true),
        new OperatorCase(
            "AFTER nao casa",
            comparacao(
                "company.openingDate", Operator.AFTER, Literal.date(LocalDate.of(2020, 1, 1))),
            comEmpresaAberta2010,
            false),
        new OperatorCase(
            "OLDER_THAN casa",
            comparacao(
                "company.openingDate", Operator.OLDER_THAN, Literal.duration(Period.ofYears(18))),
            comEmpresaAberta2000,
            true),
        new OperatorCase(
            "OLDER_THAN nao casa",
            comparacao(
                "company.openingDate", Operator.OLDER_THAN, Literal.duration(Period.ofYears(18))),
            comEmpresaAberta2020,
            false),
        new OperatorCase(
            "WITHIN_LAST casa",
            comparacao(
                "company.openingDate",
                Operator.WITHIN_LAST,
                Literal.duration(Period.ofMonths(6))),
            comEmpresaAberta2026,
            true),
        new OperatorCase(
            "WITHIN_LAST nao casa",
            comparacao(
                "company.openingDate",
                Operator.WITHIN_LAST,
                Literal.duration(Period.ofMonths(6))),
            comEmpresaAberta2020,
            false));
  }

  private record OperatorCase(
      String label, Condition condition, RiskContext context, boolean expectedMatched) {
    @Override
    public String toString() {
      return label;
    }
  }

  @Test
  void evidencia_de_is_null_omite_o_sinal_de_igual_quando_campo_by_value_esta_ausente() {
    // Corner apontado na auto-revisao do Task 3: IS_NULL nao tem valor a mostrar, entao a
    // evidencia nao deve trazer "campo=null" -- so "campo IS_NULL".
    RiskContext semEmpresa = new RiskContext("a-1", "t-1", null, null, null, null, null, AGORA);
    Condition c = comparacao("company.openingDate", Operator.IS_NULL, Literal.none());

    var resultado = evaluator.evaluate(c, semEmpresa);

    assertThat(resultado.matched()).isTrue();
    assertThat(resultado.evidences()).containsExactly("company.openingDate IS_NULL");
  }

  @Test
  void evidencia_de_is_null_em_campo_outcome_only_mantem_valor_omitido() {
    // Mesmo corner, agora sobre campo OUTCOME_ONLY: mesmo sem valor nenhum a esconder, o
    // sufixo "(valor omitido)" nao muda -- a decisao de renderizacao e so pela exposicao do
    // campo, nunca por o valor resolvido ser nulo ou nao.
    RiskContext semPerfil = new RiskContext("a-1", "t-1", null, null, null, null, null, AGORA);
    Condition c = comparacao("profile.birthDate", Operator.IS_NULL, Literal.none());

    var resultado = evaluator.evaluate(c, semPerfil);

    assertThat(resultado.matched()).isTrue();
    assertThat(resultado.evidences()).containsExactly("profile.birthDate IS_NULL (valor omitido)");
  }
}
