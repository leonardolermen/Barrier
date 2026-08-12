package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Métricas de negócio da decisão.
 *
 * <p>O serviço tinha actuator no classpath e nenhum registry: {@code /actuator/metrics} expunha JVM
 * e HTTP, nada sobre o que o produto faz. A pergunta que a auditoria fez — "se uma aprovação
 * incorreta acontecer amanhã, conseguimos descobrir por quê?" — começa por conseguir <b>notar</b>
 * que ela aconteceu.
 *
 * <p>A métrica mais valiosa aqui é a distribuição de desfechos: ela é o único sinal que pega, ao
 * mesmo tempo, mudança de regra mal calibrada, provider devolvendo lixo e fraude em escala. Se a
 * taxa de aprovação salta de 70% para 95% numa terça-feira, algo quebrou — e nenhuma das três
 * causas se anuncia de outro jeito.
 *
 * <p><b>Nada de PII em tag.</b> Documento, nome e tenant ficam de fora: métrica vai para um sistema
 * sem controle de acesso equivalente ao do banco, é retida indefinidamente e tem cardinalidade
 * ilimitada. Status e nível de risco são enumerações pequenas e não identificam ninguém.
 */
@Component
public class AssessmentMetrics {

  private final MeterRegistry registry;
  private final Timer processingTimer;

  public AssessmentMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.processingTimer =
        Timer.builder("barrier.assessment.processing")
            .description("Duração do processamento de uma avaliação (bureau + screening + decisão)")
            .publishPercentileHistogram()
            .register(registry);
  }

  /** Mede o processamento completo, incluindo as chamadas de rede. */
  public void timeProcessing(Runnable action) {
    processingTimer.record(action);
  }

  /** Conta o desfecho. É desta série que sai o alerta de taxa de aprovação fora da banda. */
  public void recordDecision(Assessment assessment) {
    registry
        .counter(
            "barrier.assessment.decisions",
            "status", assessment.status().name(),
            "level", assessment.riskLevel() == null ? "NONE" : assessment.riskLevel().name())
        .increment();
  }

  /** Tentativa de processamento que falhou (provider, banco, evidência que não coube). */
  public void recordFailure() {
    registry.counter("barrier.assessment.processing.failures").increment();
  }
}
