package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.repository.interfaces.AssessmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Idade da avaliação pendente mais antiga e volume da fila.
 *
 * <p>É o sinal que detecta <b>toda</b> forma de congelamento do pipeline com uma métrica só:
 * provider pendurado sem timeout, thread do scheduler bloqueada, banco lento, poison pill. Todos
 * têm o mesmo sintoma observável — avaliações param de concluir — e nenhum aparece no
 * {@code /actuator/health}, que só olha recursos técnicos. O incidente descrito na V023 (poison
 * pill reprocessada a cada 2s, indefinidamente) era exatamente isto: visível só como "o cliente
 * reclamou que a avaliação nunca respondeu".
 *
 * <p>Amostrado por um {@code @Scheduled} e não calculado no callback do gauge: a leitura toca o
 * banco, e um scrape de Prometheus não deve virar uma query — muito menos várias, se houver mais de
 * um coletor.
 */
@Component
public class PipelineHealthMetrics {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(PipelineHealthMetrics.class);

  private final AssessmentRepository repository;
  private final AtomicLong oldestPendingSeconds = new AtomicLong(0);
  private final AtomicLong pendingCount = new AtomicLong(0);

  public PipelineHealthMetrics(AssessmentRepository repository, MeterRegistry registry) {
    this.repository = repository;
    io.micrometer.core.instrument.Gauge.builder(
            "barrier.assessment.pending.oldest.seconds", oldestPendingSeconds, AtomicLong::get)
        .description("Idade da avaliação EM_ANALISE mais antiga ainda não concluída")
        .register(registry);
    io.micrometer.core.instrument.Gauge.builder(
            "barrier.assessment.pending.count", pendingCount, AtomicLong::get)
        .description("Avaliações aguardando processamento")
        .register(registry);
  }

  /**
   * Nunca propaga exceção para o scheduler.
   *
   * <p>Um amostrador de métrica que falha não é um incidente — é a consequência de um. Se o banco
   * está indisponível, o {@code ErrorHandler} do Spring passaria a logar um stack trace a cada
   * ciclo, poluindo exatamente o log que alguém está lendo para descobrir o que houve. O valor
   * anterior fica congelado, o que é visível no gráfico como uma linha reta, e o alerta de banco
   * fora do ar é outro.
   */
  @Scheduled(fixedDelayString = "${barrier.assessment.metrics-sample-ms:15000}")
  public void sample() {
    try {
      pendingCount.set(repository.countPending());
      Instant oldest = repository.oldestPendingCreatedAt();
      oldestPendingSeconds.set(
          oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toSeconds()));
    } catch (RuntimeException e) {
      log.debug("Não foi possível amostrar métricas do pipeline; mantendo o último valor", e);
    }
  }
}
