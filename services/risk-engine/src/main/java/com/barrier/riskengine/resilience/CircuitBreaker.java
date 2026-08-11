package com.barrier.riskengine.resilience;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Disjuntor por integração externa: depois de N falhas seguidas, para de chamar por um tempo.
 *
 * <p>Sem ele, um provider degradado continua sendo chamado a cada avaliação, e <b>cada uma paga o
 * timeout inteiro</b>. Com connect 2s + read 10s e uma fila de avaliações, um bureau que aceita a
 * conexão e não responde transforma indisponibilidade de terceiro em lentidão do próprio serviço —
 * e a lentidão é pior que a falha, porque segura threads que teriam outra coisa para fazer.
 *
 * <p>Três estados: <b>CLOSED</b> (chama normalmente), <b>OPEN</b> (recusa sem chamar, até o fim do
 * período), <b>HALF_OPEN</b> (deixa passar <i>uma</i> chamada de sondagem — se ela funcionar,
 * fecha; se falhar, abre de novo). O half-open com sondagem única é o que evita a manada: sem ele,
 * no instante em que o período expira, todas as avaliações represadas partem juntas para um
 * provider que talvez ainda esteja doente.
 *
 * <p>Escrito à mão em vez de trazer uma biblioteca de resiliência: é o único uso, cabe em uma
 * classe testável, e a dependência traria configuração e autoconfiguração para um problema que
 * aqui tem três estados e um contador.
 */
public class CircuitBreaker {

  private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final String name;
  private final int failureThreshold;
  private final Duration openDuration;

  private State state = State.CLOSED;
  private int consecutiveFailures;
  private Instant openedAt;
  private boolean probeInFlight;

  public CircuitBreaker(String name, int failureThreshold, Duration openDuration) {
    if (failureThreshold < 1) {
      throw new IllegalArgumentException("failureThreshold deve ser >= 1");
    }
    this.name = name;
    this.failureThreshold = failureThreshold;
    this.openDuration = openDuration;
  }

  /**
   * Autoriza (ou não) uma chamada ao provider, transicionando o estado quando o período de abertura
   * vence.
   *
   * @return {@code false} quando o disjuntor está aberto — o chamador deve tratar como
   *     indisponibilidade <b>sem</b> fazer a chamada
   */
  public synchronized boolean allowRequest() {
    if (state == State.CLOSED) {
      return true;
    }
    if (state == State.OPEN) {
      if (Instant.now().isBefore(openedAt.plus(openDuration))) {
        return false;
      }
      state = State.HALF_OPEN;
      probeInFlight = true;
      log.info("Disjuntor '{}' em meia-abertura: liberando uma chamada de sondagem", name);
      return true;
    }
    // HALF_OPEN: só a sondagem passa; o resto continua sendo recusado até ela responder.
    if (probeInFlight) {
      return false;
    }
    probeInFlight = true;
    return true;
  }

  public synchronized void recordSuccess() {
    consecutiveFailures = 0;
    probeInFlight = false;
    if (state != State.CLOSED) {
      log.info("Disjuntor '{}' fechado: o provider respondeu", name);
    }
    state = State.CLOSED;
  }

  public synchronized void recordFailure() {
    probeInFlight = false;
    if (state == State.HALF_OPEN) {
      abrir("a sondagem falhou");
      return;
    }
    consecutiveFailures++;
    if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
      abrir(consecutiveFailures + " falhas seguidas");
    }
  }

  private void abrir(String motivo) {
    state = State.OPEN;
    openedAt = Instant.now();
    log.warn(
        "Disjuntor '{}' ABERTO ({}); as chamadas serão recusadas pelos próximos {}",
        name,
        motivo,
        openDuration);
  }

  public synchronized State state() {
    return state;
  }

  public String name() {
    return name;
  }
}
