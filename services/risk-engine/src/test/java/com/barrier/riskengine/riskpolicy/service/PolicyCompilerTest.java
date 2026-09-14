package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.catalog.EvidenceExposure;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField;
import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyFieldType;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import java.time.Period;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyCompilerTest {

  private final PolicyCompiler compiler = new PolicyCompiler(FieldCatalog.V1);

  private Condition empresaNova() {
    return new Condition.Comparison(
        FieldCatalog.V1.find("company.openingDate").orElseThrow(),
        Operator.WITHIN_LAST,
        Literal.duration(Period.ofMonths(6)));
  }

  private PolicyRule regra(String codigo, int score) {
    return new PolicyRule(
        codigo, "Empresa nova", empresaNova(), score, Severity.MEDIUM, RiskRecommendation.REVIEW);
  }

  @Test
  void politica_valida_compila() {
    assertThatCode(() -> compiler.compile(List.of(regra("CUSTOM_EMPRESA_NOVA", 150))))
        .doesNotThrowAnyException();
  }

  @Test
  void trava_1_score_negativo_nao_compila() {
    assertThatThrownBy(() -> compiler.compile(List.of(regra("CUSTOM_AFROUXA", -100))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("score")
        .hasMessageContaining("CUSTOM_AFROUXA");
  }

  @Test
  void trava_2_codigo_fora_do_namespace_nao_compila() {
    assertThatThrownBy(() -> compiler.compile(List.of(regra("EMPRESA_NOVA", 150))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("CUSTOM_");
  }

  @Test
  void trava_2_codigo_de_regra_regulatoria_nao_compila_nem_com_prefixo() {
    assertThatThrownBy(() -> compiler.compile(List.of(regra("CUSTOM_SANCTION", 150))))
        .isInstanceOf(PolicyCompilationException.class);
    assertThatThrownBy(() -> compiler.compile(List.of(regra("SANCTION", 150))))
        .isInstanceOf(PolicyCompilationException.class);
  }

  @Test
  void trava_2_codigo_repetido_na_mesma_versao_nao_compila() {
    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(regra("CUSTOM_DUPLICADO", 100), regra("CUSTOM_DUPLICADO", 200))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("CUSTOM_DUPLICADO");
  }

  @Test
  void trava_3_operador_invalido_para_o_tipo_do_campo_nao_compila() {
    Condition invalida =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            Operator.STARTS_WITH,
            Literal.text("2026"));

    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(
                        new PolicyRule(
                            "CUSTOM_X",
                            "x",
                            invalida,
                            100,
                            Severity.LOW,
                            null))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("STARTS_WITH")
        .hasMessageContaining("DATE");
  }

  @Test
  void trava_3_campo_de_elemento_fora_de_any_of_nao_compila() {
    Condition solto =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.partners[].foreign").orElseThrow(),
            Operator.EQ,
            Literal.bool(true));

    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(new PolicyRule("CUSTOM_Y", "y", solto, 100, Severity.LOW, null))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("AnyOf");
  }

  @Test
  void trava_4_arvore_funda_demais_nao_compila() {
    Condition fundo = empresaNova();
    for (int i = 0; i < PolicyCompiler.MAX_DEPTH + 1; i++) {
      fundo = new Condition.Not(fundo);
    }

    Condition finalFundo = fundo;
    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(new PolicyRule("CUSTOM_Z", "z", finalFundo, 100, Severity.LOW, null))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("profundidade");
  }

  @Test
  void score_zero_compila_porque_regra_pode_so_recomendar() {
    assertThatCode(() -> compiler.compile(List.of(regra("CUSTOM_SO_REVISA", 0))))
        .doesNotThrowAnyException();
  }

  @Test
  void mensagem_de_erro_cita_campo_operador_e_trava() {
    Condition invalida =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            Operator.STARTS_WITH,
            Literal.text("x"));

    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(new PolicyRule("CUSTOM_W", "w", invalida, 1, Severity.LOW, null))))
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .contains("company.openingDate")
                    .contains("STARTS_WITH"));
  }

  /**
   * Cobertura adicional (não pedida literalmente no brief, mas parte do que o desenho §5.5
   * chama de trava 3: "todo PolicyField citado existe no catálogo pelo id"): um campo que não
   * vem do {@code FieldCatalog} não pode entrar numa política, mesmo que o id pareça plausível.
   */
  @Test
  void trava_3_campo_desconhecido_no_catalogo_nao_compila() {
    PolicyField fantasma =
        new PolicyField(
            "nao.existe.no.catalogo",
            PolicyFieldType.STRING,
            ContextInput.PROFILE,
            EvidenceExposure.BY_VALUE,
            null,
            o -> null);
    Condition invalida = new Condition.Comparison(fantasma, Operator.EQ, Literal.text("x"));

    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(
                        new PolicyRule("CUSTOM_FANTASMA", "f", invalida, 1, Severity.LOW, null))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("nao.existe.no.catalogo");
  }

  /**
   * Comparação nunca é sobre a lista inteira -- é isso que o {@code AnyOf} existe para resolver.
   */
  @Test
  void trava_3_comparison_sobre_campo_lista_nao_compila() {
    Condition invalida =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.partners").orElseThrow(),
            Operator.IS_NULL,
            Literal.none());

    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(new PolicyRule("CUSTOM_LISTA", "l", invalida, 1, Severity.LOW, null))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("LIST")
        .hasMessageContaining("AnyOf");
  }

  /**
   * {@code AnyOf.listField()} tem que ser um campo LIST de verdade -- senão o avaliador estoura
   * {@code ClassCastException} tentando tratar o valor resolvido como lista (ele não embrulha
   * esse caminho em try/catch, diferente da comparação). É a compilação que impede o crash.
   */
  @Test
  void trava_3_any_of_sobre_campo_que_nao_e_lista_nao_compila() {
    Condition invalida =
        new Condition.AnyOf(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            new Condition.Comparison(
                FieldCatalog.V1.find("company.partners[].foreign").orElseThrow(),
                Operator.EQ,
                Literal.bool(true)));

    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(
                        new PolicyRule(
                            "CUSTOM_ANYOF_ERRADO", "a", invalida, 1, Severity.LOW, null))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("company.openingDate")
        .hasMessageContaining("LIST");
  }

  /**
   * Campo de elemento dentro do {@code AnyOf} da lista errada -- {@code
   * company.partners[].foreign} só pode aparecer dentro do {@code AnyOf} de {@code
   * company.partners}, nunca dentro de um {@code AnyOf} de outra lista.
   */
  @Test
  void trava_3_campo_de_elemento_dentro_do_any_of_da_lista_errada_nao_compila() {
    Condition invalida =
        new Condition.AnyOf(
            FieldCatalog.V1.find("screening.hits").orElseThrow(),
            new Condition.Comparison(
                FieldCatalog.V1.find("company.partners[].foreign").orElseThrow(),
                Operator.EQ,
                Literal.bool(true)));

    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(
                        new PolicyRule(
                            "CUSTOM_ANYOF_LISTA_ERRADA", "a", invalida, 1, Severity.LOW, null))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("company.partners[].foreign")
        .hasMessageContaining("screening.hits");
  }
}
