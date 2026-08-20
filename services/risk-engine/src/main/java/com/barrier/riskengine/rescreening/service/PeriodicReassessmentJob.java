package com.barrier.riskengine.rescreening.service;

import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.commons.jobs.SingletonJobLock;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import com.barrier.riskengine.rescreening.policy.domain.ReassessmentTrigger;
import com.barrier.riskengine.rescreening.policy.service.ReassessmentPolicy;
import com.barrier.riskengine.riskstate.domain.SubjectRiskState;
import com.barrier.riskengine.riskstate.repository.interfaces.SubjectRiskStateRepository;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reavaliação periódica (re-KYC): varre uma vez por dia os clientes cujo intervalo do nível de risco
 * venceu e submete avaliação nova.
 *
 * <p><b>É o gatilho que faltava.</b> A {@link ReassessmentPolicy} sabia decidir sobre
 * {@code PERIODIC} desde o ADR-0019, mas nada a acionava: o intervalo por faixa (1095/730/365/183
 * dias) funcionava só como <i>freio</i>, nunca como <i>gatilho</i>. Sem este job, "cliente bom se
 * reavalia a cada 3 anos" era uma frase, não um comportamento — e a reavaliação periódica que a
 * projeção de risco corrente (F3) existia para destravar não acontecia.
 *
 * <p><b>Custo é o risco desta frente, não corretude.</b> Cada cliente devido gera uma avaliação
 * completa, com consulta paga de bureau. Por isso, três travas — as mesmas em espírito das do
 * {@code RescreeningService}:
 *
 * <ul>
 *   <li><b>desligado por padrão</b> ({@code barrier.rescreening.periodic.enabled}). Frente que
 *       submete avaliação em massa não nasce ligada;
 *   <li><b>teto por execução</b> ({@code max-per-run}). Ligar isto numa base grande com anos de
 *       histórico tornaria devido um lote enorme de uma vez — o teto transforma o acúmulo em fila
 *       drenada ao longo de dias, e a ordem (mais antigo primeiro) garante que ninguém fica para
 *       trás;
 *   <li><b>não empilha</b>: cliente com avaliação já em análise é pulado, porque aquela vai
 *       concluir e atualizar a projeção — submeter outra é pagar duas vezes pelo mesmo re-KYC.
 * </ul>
 *
 * <p>A consulta faz pré-filtro pelo <b>menor</b> intervalo da tabela e a política decide caso a
 * caso pelo nível de cada cliente. Os prazos vivem só na política; replicá-los em SQL criaria uma
 * segunda cópia, e duas cópias divergem.
 *
 * <p><b>Limitação conhecida:</b> o teto é global, e a ordem por antiguidade não isola tenants — um
 * parceiro com base muito maior consome a maior parte da cota diária. É o mesmo problema que a cota
 * por tenant do ADR-0015 resolve para a ingestão em massa, e a solução deve ser a mesma quando
 * houver.
 */
@Service
public class PeriodicReassessmentJob {

  private static final Logger log = LoggerFactory.getLogger(PeriodicReassessmentJob.class);

  private final SubjectRiskStateRepository riskState;
  private final ReassessmentPolicy policy;
  private final AssessmentService assessments;
  private final SubjectService subjects;
  private final SingletonJobLock jobLock;
  private final boolean enabled;
  private final int maxPerRun;

  public PeriodicReassessmentJob(
      SubjectRiskStateRepository riskState,
      ReassessmentPolicy policy,
      AssessmentService assessments,
      SubjectService subjects,
      SingletonJobLock jobLock,
      @Value("${barrier.rescreening.periodic.enabled:false}") boolean enabled,
      @Value("${barrier.rescreening.periodic.max-per-run:200}") int maxPerRun) {
    this.riskState = riskState;
    this.policy = policy;
    this.assessments = assessments;
    this.subjects = subjects;
    this.jobLock = jobLock;
    this.enabled = enabled;
    this.maxPerRun = maxPerRun;
  }

  /**
   * Roda de madrugada por padrão: o lote compete com o tráfego de onboarding pelo mesmo pipeline
   * (ver ADR-0015 — a fila é global e não isola faixas), e é melhor que ele caia no horário de menor
   * volume.
   */
  @Scheduled(cron = "${barrier.rescreening.periodic.cron:0 30 3 * * *}")
  public void run() {
    if (!enabled) {
      return;
    }
    jobLock.runIfLeader(
        "periodic-reassessment", Duration.ofHours(1), Duration.ofHours(2), this::runOnce);
  }

  /**
   * O lote roda <b>uma vez no cluster</b>. Sem o lock, cinco réplicas executavam o mesmo cron e o
   * {@code max-per-run} de 200 virava 1000 avaliações por noite — cada uma com consulta paga de
   * bureau. O teto continuaria escrito no código e violado na prática, que é a pior combinação:
   * um controle de custo que a leitura do código diz existir e a fatura diz que não.
   *
   * <p>O piso de 1h é o que impede uma réplica com cron atrasado de reexecutar a mesma janela e
   * dobrar o lote.
   */
  private void runOnce() {
    try {
      int criadas = reassessDue();
      if (criadas > 0) {
        log.warn("Reavaliação periódica: {} avaliação(ões) criada(s) nesta execução", criadas);
      }
    } catch (RuntimeException e) {
      // Nunca propaga para o scheduler: falhar hoje não pode impedir a execução de amanhã, e a
      // varredura é idempotente por natureza (o que ficou devido continua devido).
      log.error("Reavaliação periódica falhou nesta execução", e);
    }
  }

  /** Visível para teste. @return quantas avaliações foram criadas */
  public int reassessDue() {
    List<SubjectRiskState> candidatos =
        riskState.findDueForPeriodicReview(ReassessmentPolicy.menorIntervalo(), maxPerRun);
    if (candidatos.isEmpty()) {
      return 0;
    }

    int criadas = 0;
    for (SubjectRiskState corrente : candidatos) {
      criadas += submit(corrente) ? 1 : 0;
    }
    log.info(
        "Reavaliação periódica: {} candidato(s) avaliado(s) pela política, {} reavaliação(ões)"
            + " criada(s)",
        candidatos.size(),
        criadas);
    return criadas;
  }

  /** Falha de um cliente não interrompe os demais — mesmo princípio do rescreening por lista. */
  private boolean submit(SubjectRiskState corrente) {
    try {
      if (assessments.existsPendingBySubject(corrente.subjectId(), corrente.tenantId())) {
        return false;
      }
      String detail =
          corrente.level().name()
              + "@"
              + ReassessmentPolicy.intervaloMinimo(corrente.level()).toDays()
              + "d";
      if (!policy
          .decide(
              corrente.subjectId(),
              corrente.tenantId(),
              ReassessmentTrigger.PERIODIC,
              detail,
              false)
          .reassess()) {
        return false;
      }
      Subject subject = subjects.findById(corrente.subjectId(), corrente.tenantId());
      assessments.submit(
          SubmitAssessmentCommand.periodicReview(
              corrente.tenantId(),
              DocumentType.valueOf(subject.documentType()),
              subject.document(),
              subject.name(),
              detail));
      return true;
    } catch (RuntimeException e) {
      log.error(
          "Reavaliação periódica falhou para o subject {} do tenant {}",
          corrente.subjectId(),
          corrente.tenantId(),
          e);
      return false;
    }
  }

  /** Intervalo mínimo do pior nível — o pré-filtro. Exposto para log e teste. */
  static Duration prefiltro() {
    return ReassessmentPolicy.menorIntervalo();
  }
}
