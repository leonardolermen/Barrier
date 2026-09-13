package com.barrier.riskengine.risk.rule.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RiskContextTest {

  @Test
  void carrega_o_instante_de_referencia_da_decisao() {
    Instant quando = Instant.parse("2026-03-01T10:00:00Z");
    RiskContext ctx =
        new RiskContext("a-1", "tenant-1", null, null, null, null, null, quando);

    assertThat(ctx.referenceInstant()).isEqualTo(quando);
  }

  @Test
  void recusa_instante_nulo_porque_regra_de_data_nao_teria_relogio() {
    assertThatThrownBy(
            () -> new RiskContext("a-1", "tenant-1", null, null, null, null, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("referenceInstant");
  }
}
