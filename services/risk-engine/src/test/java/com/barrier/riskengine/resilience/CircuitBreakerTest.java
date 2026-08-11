package com.barrier.riskengine.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

  private static CircuitBreaker breaker(Duration openDuration) {
    return new CircuitBreaker("bureau-teste", 3, openDuration);
  }

  @Test
  void fechadoDeixaPassar() {
    assertThat(breaker(Duration.ofSeconds(30)).allowRequest()).isTrue();
  }

  @Test
  void abreDepoisDoLimiteDeFalhasSeguidas() {
    CircuitBreaker cb = breaker(Duration.ofSeconds(30));

    cb.recordFailure();
    cb.recordFailure();
    assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);

    cb.recordFailure();

    assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(cb.allowRequest()).isFalse();
  }

  /** O contador é de falhas <b>seguidas</b>: um sucesso no meio zera. */
  @Test
  void sucessoNoMeioZeraOContador() {
    CircuitBreaker cb = breaker(Duration.ofSeconds(30));

    cb.recordFailure();
    cb.recordFailure();
    cb.recordSuccess();
    cb.recordFailure();
    cb.recordFailure();

    assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  /** Vencido o período, passa <b>uma</b> sondagem — não a manada represada. */
  @Test
  void meiaAberturaLiberaApenasUmaChamada() {
    CircuitBreaker cb = breaker(Duration.ZERO);
    abrir(cb);

    assertThat(cb.allowRequest()).isTrue();
    assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    assertThat(cb.allowRequest()).isFalse();
    assertThat(cb.allowRequest()).isFalse();
  }

  @Test
  void sondagemBemSucedidaFechaODisjuntor() {
    CircuitBreaker cb = breaker(Duration.ZERO);
    abrir(cb);
    cb.allowRequest();

    cb.recordSuccess();

    assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(cb.allowRequest()).isTrue();
  }

  /** Sondagem que falha reabre na hora, sem esperar juntar o limite de novo. */
  @Test
  void sondagemQueFalhaReabre() {
    CircuitBreaker cb = breaker(Duration.ZERO);
    abrir(cb);
    cb.allowRequest();

    cb.recordFailure();

    assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void abertoRecusaEnquantoOPeriodoNaoVence() {
    CircuitBreaker cb = breaker(Duration.ofMinutes(5));
    abrir(cb);

    assertThat(cb.allowRequest()).isFalse();
    assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  private static void abrir(CircuitBreaker cb) {
    cb.recordFailure();
    cb.recordFailure();
    cb.recordFailure();
  }
}
