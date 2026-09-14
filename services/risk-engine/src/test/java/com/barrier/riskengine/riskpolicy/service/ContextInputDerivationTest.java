package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import java.time.Period;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code requires()} de uma regra custom não é declarado por humano: é derivado caminhando a
 * árvore e acumulando o {@link ContextInput} de todo campo referenciado. Sem lista escrita à
 * mão, não há como declarar de menos ou de mais.
 */
class ContextInputDerivationTest {

  @Test
  void deriva_os_insumos_dos_campos_que_a_arvore_referencia() {
    Condition c =
        new Condition.And(
            List.of(
                new Condition.Comparison(
                    FieldCatalog.V1.find("company.openingDate").orElseThrow(),
                    Operator.WITHIN_LAST,
                    Literal.duration(Period.ofMonths(6))),
                new Condition.Comparison(
                    FieldCatalog.V1.find("profile.nationality").orElseThrow(),
                    Operator.NEQ,
                    Literal.text("BRASILEIRA"))));

    assertThat(ContextInputDerivation.of(c))
        .containsExactlyInAnyOrder(ContextInput.COMPANY, ContextInput.PROFILE);
  }

  @Test
  void any_of_inclui_o_insumo_da_lista_e_o_dos_campos_de_elemento() {
    Condition c =
        new Condition.AnyOf(
            FieldCatalog.V1.find("company.partners").orElseThrow(),
            new Condition.Comparison(
                FieldCatalog.V1.find("company.partners[].foreign").orElseThrow(),
                Operator.EQ,
                Literal.bool(true)));

    assertThat(ContextInputDerivation.of(c)).containsExactly(ContextInput.COMPANY);
  }
}
