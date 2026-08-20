package com.barrier.commons.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

/**
 * A amarra entre o teto de trabalhadores e o pool de conexões, que antes existia só em comentário.
 */
class WorkerPoolReadinessGuardTest {

  private static final String PROP = "barrier.assessment.workers";
  private static final int RESERVA = 2;

  @Test
  void aceitaWorkersDentroDoTeto() {
    assertThatCode(() -> guard(6, 8).run(null)).doesNotThrowAnyException();
  }

  /** O limite é inclusivo: pool 8 com reserva 2 permite exatamente 6. */
  @Test
  void aceitaExatamenteNoLimite() {
    assertThatCode(() -> guard(6, 8).run(null)).doesNotThrowAnyException();
    assertThatThrownBy(() -> guard(7, 8).run(null)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void recusaWorkersAlemDoTeto() {
    assertThatThrownBy(() -> guard(12, 8).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(PROP)
        .hasMessageContaining("o teto é 6");
  }

  /**
   * A mensagem tem de dizer o que mexer, e as DUAS saídas: quem lê isso está numa subida quebrada
   * e não deve precisar refazer a conta de cabeça.
   */
  @Test
  void mensagemDizComoCorrigir() {
    assertThatThrownBy(() -> guard(12, 8).run(null))
        .satisfies(
            e -> {
              assertThat(e).hasMessageContaining("Reduza " + PROP + " para no máximo 6");
              assertThat(e).hasMessageContaining("maximum-pool-size para pelo menos 14");
            });
  }

  /** Zero worker não estoura o pool e mesmo assim é configuração quebrada: a fila nunca drena. */
  @Test
  void recusaZeroWorkers() {
    assertThatThrownBy(() -> guard(0, 8).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nunca drena");
  }

  private static WorkerPoolReadinessGuard guard(int workers, int pool) {
    HikariDataSource ds = new HikariDataSource();
    ds.setMaximumPoolSize(pool);
    return new WorkerPoolReadinessGuard(ds, PROP, workers, RESERVA);
  }
}
