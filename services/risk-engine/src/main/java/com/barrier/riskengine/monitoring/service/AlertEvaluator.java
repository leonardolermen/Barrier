package com.barrier.riskengine.monitoring.service;

import com.barrier.riskengine.assessment.repository.interfaces.AssessmentRepository;
import com.barrier.riskengine.monitoring.domain.Alert;
import com.barrier.riskengine.monitoring.service.interfaces.AlertNotifier;
import com.barrier.riskengine.monitoring.service.interfaces.AlertRule;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Mede o pipeline uma vez por ciclo e submete o resultado a todas as regras de alerta.
 *
 * <p>Fecha o modo de falha que o teste de carga do ADR-0015 expôs: métrica existia
 * ({@code PipelineHealthMetrics}) e nada comparava nada contra nada, então 69.809 avaliações
 * presas em EM_ANALISE não produziram sinal algum.
 *
 * <p><b>Uma medição, N regras.</b> O snapshot é montado aqui e passado pronto: com uma query por
 * regra, o monitoramento viraria carga sobre a tabela mais quente do sistema — e cresceria a cada
 * alerta novo.
 *
 * <p><b>Dedup por código.</b> Um alerta repete no máximo a cada {@code repeat-interval}. Sem isso,
 * uma fila represada por três horas com ciclo de cinco minutos produziria trinta e seis mensagens
 * idênticas, e a trigésima sexta seria lida com menos atenção que a primeira.
 */
@Service
@ConditionalOnProperty(value = "barrier.monitoring.alerts.enabled", havingValue = "true")
public class AlertEvaluator {

  private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

  private final AssessmentRepository repository;
  private final List<AlertRule> rules;
  private final List<AlertNotifier> notifiers;
  private final int historyDays;
  private final Duration repeatInterval;
  private final Map<String, Instant> lastNotified = new HashMap<>();

  public AlertEvaluator(
      AssessmentRepository repository,
      List<AlertRule> rules,
      List<AlertNotifier> notifiers,
      @Value("${barrier.monitoring.history-days:7}") int historyDays,
      @Value("${barrier.monitoring.repeat-interval:PT1H}") Duration repeatInterval) {
    this.repository = repository;
    this.rules = rules;
    this.notifiers = notifiers;
    this.historyDays = historyDays;
    this.repeatInterval = repeatInterval;
  }

  /**
   * Nunca propaga exceção, mesma postura do {@code PipelineHealthMetrics}: um avaliador de alerta
   * que falha não é o incidente — é consequência de um, e encher o log de stack trace a cada ciclo
   * atrapalha justamente quem está lendo esse log para descobrir o que houve.
   */
  @Scheduled(fixedDelayString = "${barrier.monitoring.evaluate-delay-ms:300000}")
  public void evaluate() {
    try {
      PipelineSnapshot snapshot = snapshot(Instant.now());
      for (AlertRule rule : rules) {
        rule.evaluate(snapshot).ifPresent(this::dispatch);
      }
    } catch (RuntimeException e) {
      log.debug("Não foi possível avaliar os alertas neste ciclo", e);
    }
  }

  /**
   * Visível para teste: monta o snapshot para um instante determinado.
   *
   * <p><b>Janela deslizante de 60 minutos, não a hora do relógio.</b> Com hora cheia, às 14h05 o
   * observado seriam cinco minutos de tráfego comparados contra sessenta minutos históricos — e
   * {@code vol_hora_baixo} dispararia no início de toda hora, todo dia. O ecossistema Origem
   * resolve isso normalizando pela fração do período decorrida; medir sempre os últimos 60 minutos
   * (e comparar com os mesmos 60 minutos de dias anteriores) elimina o viés na origem, sem fator de
   * correção para alguém errar depois.
   */
  PipelineSnapshot snapshot(Instant now) {
    Instant inicio = now.minus(1, ChronoUnit.HOURS);

    List<PipelineWindowStats> historico = new ArrayList<>();
    for (int dia = 1; dia <= historyDays; dia++) {
      historico.add(window(inicio.minus(dia, ChronoUnit.DAYS), now.minus(dia, ChronoUnit.DAYS)));
    }

    Instant maisAntiga = repository.oldestPendingCreatedAt();
    return new PipelineSnapshot(
        window(inicio, now),
        historico,
        maisAntiga == null ? Duration.ZERO : Duration.between(maisAntiga, now));
  }

  private PipelineWindowStats window(Instant from, Instant to) {
    return new PipelineWindowStats(
        repository.countCreatedBetween(from, to),
        repository.countCompletedByStatusBetween(from, to),
        repository.countAutoApprovedBetween(from, to));
  }

  private void dispatch(Alert alert) {
    Instant ultima = lastNotified.get(alert.code());
    if (ultima != null && ultima.isAfter(Instant.now().minus(repeatInterval))) {
      return;
    }
    lastNotified.put(alert.code(), Instant.now());
    notifiers.forEach(
        notifier -> {
          try {
            notifier.notify(alert);
          } catch (RuntimeException e) {
            // Canal fora do ar não pode impedir os outros canais nem derrubar o ciclo.
            log.warn("Falha ao notificar alerta {} em {}", alert.code(), notifier.getClass(), e);
          }
        });
  }
}
