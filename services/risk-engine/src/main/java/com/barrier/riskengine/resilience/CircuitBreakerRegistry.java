package com.barrier.riskengine.resilience;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Um disjuntor por integração, criado sob demanda e compartilhado por toda a instância — é o estado
 * acumulado entre chamadas que dá sentido ao mecanismo.
 *
 * <p>O estado é <b>por instância</b>, de propósito: cada réplica aprende com as próprias chamadas.
 * Estado compartilhado exigiria coordenação (e uma dependência a mais no caminho da decisão) para
 * ganhar pouco — as réplicas veem o mesmo provider degradado e abrem sozinhas, com no máximo
 * algumas chamadas extras de diferença.
 */
@Component
public class CircuitBreakerRegistry {

  private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
  private final int failureThreshold;
  private final Duration openDuration;

  public CircuitBreakerRegistry(
      @Value("${barrier.resilience.failure-threshold:5}") int failureThreshold,
      @Value("${barrier.resilience.open-duration:PT30S}") Duration openDuration) {
    this.failureThreshold = failureThreshold;
    this.openDuration = openDuration;
  }

  public CircuitBreaker forName(String name) {
    return breakers.computeIfAbsent(
        name, key -> new CircuitBreaker(key, failureThreshold, openDuration));
  }
}
