package com.barrier.riskengine.mesa.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.assessment.service.AssessmentCompletedListener;
import com.barrier.riskengine.mesa.domain.CaseQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Abre e fecha o caso da mesa conforme o desfecho da avaliação.
 *
 * <p>Implementa {@link AssessmentCompletedListener} — o mesmo ponto de inversão que a projeção de
 * risco corrente usa. {@code assessment} declara o fato e não sabe quem reage; sem isso,
 * {@code assessment → mesa → assessment} seria ciclo de módulo.
 *
 * <p>Roteamento:
 *
 * <ul>
 *   <li>{@code EM_REVISAO} → abre em {@code ANALISE_PADRAO}: é EDD, pede julgamento humano;
 *   <li>{@code SOLICITAR_DOCUMENTO} → abre em {@code AGUARDANDO_PARCEIRO}. O status já existia
 *       justamente para tirar da fila de EDD o que não pede analista, e sem <b>nenhuma</b> fila
 *       esses casos ficavam invisíveis: ninguém os contava, ninguém os cobrava;
 *   <li>desfecho final (APROVADO/REPROVADO) → fecha o caso, se houver.
 * </ul>
 *
 * <p>Um caso aberto em {@code AGUARDANDO_PARCEIRO} pelo próprio motor <b>não</b> nasce com pausa de
 * SLA: pausa exige o par pedido/recebimento registrado por um humano. Enquanto ninguém pedir nada
 * formalmente, o relógio corre — e corre contra a mesa, que é o lado conservador.
 */
@Component
public class MesaCaseRouter implements AssessmentCompletedListener {

  private static final Logger log = LoggerFactory.getLogger(MesaCaseRouter.class);

  private final CaseService cases;

  public MesaCaseRouter(CaseService cases) {
    this.cases = cases;
  }

  @Override
  public void onCompleted(Assessment assessment, Integer score, String engineVersion) {
    if (assessment.status() == AssessmentStatus.EM_REVISAO) {
      cases.open(assessment.id().value(), assessment.tenantId(), CaseQueue.ANALISE_PADRAO);
      return;
    }
    if (assessment.status() == AssessmentStatus.SOLICITAR_DOCUMENTO) {
      cases.open(assessment.id().value(), assessment.tenantId(), CaseQueue.AGUARDANDO_PARCEIRO);
      return;
    }
    if (assessment.status() == AssessmentStatus.APROVADO
        || assessment.status() == AssessmentStatus.REPROVADO) {
      String actor = assessment.reviewedBy() == null ? "motor" : assessment.reviewedBy();
      cases.close(assessment.id().value(), assessment.tenantId(), actor, assessment.decision());
      log.debug("Caso da avaliação {} fechado por {}", assessment.id().asString(), actor);
    }
  }
}
