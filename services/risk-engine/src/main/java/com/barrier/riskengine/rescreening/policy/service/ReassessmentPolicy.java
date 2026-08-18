package com.barrier.riskengine.rescreening.policy.service;

import com.barrier.riskengine.rescreening.policy.domain.ReassessmentDecision;
import com.barrier.riskengine.rescreening.policy.domain.ReassessmentTrigger;
import com.barrier.riskengine.rescreening.policy.repository.interfaces.ReassessmentDecisionRepository;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.riskstate.domain.SubjectRiskState;
import com.barrier.riskengine.riskstate.service.SubjectRiskStateService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decide se reavaliar um cliente é legítimo — gatilho, alteração material e intervalo mínimo por
 * nível de risco (ADR-0019) — e registra a decisão, inclusive quando é "não".
 *
 * <p><b>Isto não substitui as travas de avalanche</b> do {@code RescreeningService} (linha de base,
 * teto por importação, uma avaliação por subject/tenant por importação) nem a janela de dedup do
 * assurance. Aquelas protegem o sistema de volume; esta responde se o cliente <i>deveria</i> ser
 * reavaliado. São perguntas ortogonais e continuam ambas valendo.
 *
 * <p><b>Intervalo mínimo nunca se aplica a fato adverso novo</b> — ver
 * {@link ReassessmentTrigger}, onde o bypass é propriedade do gatilho.
 */
@Service
public class ReassessmentPolicy {

  private static final Logger log = LoggerFactory.getLogger(ReassessmentPolicy.class);

  /**
   * Intervalo mínimo por nível corrente, na escala do Barrier (maior = pior). Cliente sem projeção
   * cai no pior caso: desconhecido não é sinônimo de bom, e o erro barato aqui é reavaliar demais.
   */
  private static final Duration DESCONHECIDO = Duration.ofDays(183);

  private final SubjectRiskStateService riskState;
  private final ReassessmentDecisionRepository repository;

  public ReassessmentPolicy(
      SubjectRiskStateService riskState, ReassessmentDecisionRepository repository) {
    this.riskState = riskState;
    this.repository = repository;
  }

  @Transactional
  public ReassessmentDecision decide(
      UUID subjectId,
      String tenantId,
      ReassessmentTrigger trigger,
      String triggerDetail,
      boolean materialChange) {

    Optional<SubjectRiskState> corrente = riskState.find(subjectId, tenantId);
    RiskLevel nivel = corrente.map(SubjectRiskState::level).orElse(null);

    if (trigger.requiresMaterialChange() && !materialChange) {
      return registrar(
          ReassessmentDecision.nao(
              subjectId,
              tenantId,
              trigger,
              triggerDetail,
              ReassessmentDecision.SEM_ALTERACAO_MATERIAL,
              nivel));
    }

    if (trigger.respectsMinimumInterval() && corrente.isPresent()) {
      Duration minimo = intervaloMinimo(nivel);
      Instant ultimaDecisao = corrente.get().evaluatedAt();
      if (ultimaDecisao.isAfter(Instant.now().minus(minimo))) {
        return registrar(
            ReassessmentDecision.nao(
                subjectId,
                tenantId,
                trigger,
                triggerDetail,
                ReassessmentDecision.INTERVALO_MINIMO,
                nivel));
      }
    }

    return registrar(
        ReassessmentDecision.sim(subjectId, tenantId, trigger, triggerDetail, nivel));
  }

  /**
   * Intervalo mínimo desde a última decisão, por nível corrente (ADR-0019, tabela).
   *
   * <p>Cliente bom se reavalia a cada 3 anos; cliente ruim, a cada 6 meses. É contraintuitivo até
   * se lembrar de que reavaliar custa consulta paga: o gasto vai para onde o risco está.
   */
  public static Duration intervaloMinimo(RiskLevel nivel) {
    if (nivel == null) {
      return DESCONHECIDO;
    }
    return switch (nivel) {
      case LOW -> Duration.ofDays(1095);
      case MEDIUM -> Duration.ofDays(730);
      case HIGH -> Duration.ofDays(365);
      case CRITICAL -> Duration.ofDays(183);
    };
  }

  /**
   * Menor intervalo da tabela — o do pior nível de risco. É o pré-filtro do job periódico: nada
   * mais novo que isto pode estar vencido para nenhum nível, então a varredura não precisa olhar.
   */
  public static Duration menorIntervalo() {
    return java.util.Arrays.stream(RiskLevel.values())
        .map(ReassessmentPolicy::intervaloMinimo)
        .min(Duration::compareTo)
        .orElse(DESCONHECIDO);
  }

  private ReassessmentDecision registrar(ReassessmentDecision decision) {
    if (!decision.reassess()) {
      log.debug(
          "Reavaliação por {} não realizada para o subject {}: {}",
          decision.trigger(),
          decision.subjectId(),
          decision.reason());
    }
    return repository.save(decision);
  }
}
