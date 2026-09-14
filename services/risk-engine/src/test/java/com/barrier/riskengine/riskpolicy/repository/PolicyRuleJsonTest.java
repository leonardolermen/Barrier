package com.barrier.riskengine.riskpolicy.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.catalog.EvidenceExposure;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyFieldType;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import java.time.Period;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * A defesa central da Task 6: {@link PolicyRule} nunca grava um {@code PolicyField} inteiro em
 * {@code rules_json}, só o {@code id}. A leitura sempre resolve pelo {@link FieldCatalog} — mesmo
 * quando o JSON gravado tenta declarar tipo ou exposição diferentes do que o catálogo diz.
 */
class PolicyRuleJsonTest {

  private final PolicyRuleJson json = new PolicyRuleJson(new ObjectMapper(), FieldCatalog.V1);

  @Test
  void ida_e_volta_preserva_a_regra() {
    Condition when =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            Operator.WITHIN_LAST,
            Literal.duration(Period.ofMonths(6)));
    PolicyRule original =
        new PolicyRule(
            "CUSTOM_A", "empresa nova", when, 150, Severity.MEDIUM, RiskRecommendation.REVIEW);

    String texto = json.toJson(List.of(original));
    List<PolicyRule> lidas = json.fromJson(texto);

    assertThat(lidas).containsExactly(original);
  }

  /**
   * {@code profile.birthDate} é {@code OUTCOME_ONLY} no catálogo real -- o valor nunca pode
   * aparecer na evidência. O JSON abaixo é uma linha gravada (ou adulterada) que tenta anexar
   * {@code exposure=BY_VALUE} e {@code type=BOOLEAN} junto do {@code fieldId} -- propriedades que
   * o formato de fio nem declara. A leitura tem de devolver exatamente o {@link
   * com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField} do catálogo, nunca um construído
   * a partir dessas propriedades forjadas.
   */
  @Test
  void campo_com_type_e_exposure_forjados_no_json_e_resolvido_pelo_catalogo_nao_pelo_json() {
    String jsonAdulterado =
        """
        [
          {
            "code": "CUSTOM_ATAQUE",
            "name": "tenta vazar nascimento",
            "when": {
              "type": "cmp",
              "fieldId": "profile.birthDate",
              "exposure": "BY_VALUE",
              "fieldType": "BOOLEAN",
              "parentListId": "algo-forjado",
              "op": "EQ",
              "value": {"type": "text", "value": "X"}
            },
            "score": 10,
            "severity": "LOW",
            "recommendation": null
          }
        ]
        """;

    List<PolicyRule> lidas = json.fromJson(jsonAdulterado);

    assertThat(lidas).hasSize(1);
    var comparison = (Condition.Comparison) lidas.get(0).when();
    var campoDoCatalogo = FieldCatalog.V1.find("profile.birthDate").orElseThrow();

    // Mesmo objeto do catálogo -- não um PolicyField reconstruído a partir do JSON.
    assertThat(comparison.field()).isSameAs(campoDoCatalogo);
    assertThat(comparison.field().exposure()).isEqualTo(EvidenceExposure.OUTCOME_ONLY);
    assertThat(comparison.field().type()).isEqualTo(PolicyFieldType.DATE);
    assertThat(comparison.field().parentListId()).isNull();
  }

  @Test
  void id_de_campo_que_nao_existe_no_catalogo_e_erro_alto_nunca_silencioso() {
    String jsonComCampoInexistente =
        """
        [
          {
            "code": "CUSTOM_B",
            "name": "campo removido",
            "when": {"type": "cmp", "fieldId": "campo.que.nao.existe.mais", "op": "EQ",
                      "value": {"type": "text", "value": "X"}},
            "score": 10,
            "severity": "LOW",
            "recommendation": null
          }
        ]
        """;

    assertThatThrownBy(() -> json.fromJson(jsonComCampoInexistente))
        .isInstanceOf(UnknownPolicyFieldException.class)
        .hasMessageContaining("campo.que.nao.existe.mais");
  }
}
