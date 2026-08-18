package com.barrier.riskengine.rescreening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import com.barrier.riskengine.rescreening.policy.domain.ReassessmentDecision;
import com.barrier.riskengine.rescreening.policy.domain.ReassessmentTrigger;
import com.barrier.riskengine.rescreening.policy.service.ReassessmentPolicy;
import com.barrier.riskengine.rescreening.service.PeriodicReassessmentJob;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.riskstate.domain.SubjectRiskState;
import com.barrier.riskengine.riskstate.repository.interfaces.SubjectRiskStateRepository;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * O gatilho da reavaliação periódica. O que estes testes protegem é sobretudo o <b>custo</b>: cada
 * cliente devido gera uma avaliação completa, com consulta paga de bureau.
 */
@ExtendWith(MockitoExtension.class)
class PeriodicReassessmentJobTest {

  private static final String TENANT = "acme";

  @Mock SubjectRiskStateRepository riskState;
  @Mock ReassessmentPolicy policy;
  @Mock AssessmentService assessments;
  @Mock SubjectService subjects;

  private PeriodicReassessmentJob job;

  @BeforeEach
  void setUp() {
    job = new PeriodicReassessmentJob(riskState, policy, assessments, subjects, true, 200);
    lenient()
        .when(subjects.findById(any(), anyString()))
        .thenReturn(Subject.create("CPF", "11144477735", "Fulano"));
  }

  @Test
  void cliente_devido_e_reavaliado_com_origem_periodica() {
    SubjectRiskState devido = corrente(RiskLevel.CRITICAL, 400);
    when(riskState.findDueForPeriodicReview(any(), anyInt())).thenReturn(List.of(devido));
    when(assessments.existsPendingBySubject(any(), anyString())).thenReturn(false);
    permite(devido);

    assertThat(job.reassessDue()).isEqualTo(1);

    var captor = org.mockito.ArgumentCaptor.forClass(SubmitAssessmentCommand.class);
    verify(assessments).submit(captor.capture());
    assertThat(captor.getValue().origin())
        .isEqualTo(com.barrier.riskengine.assessment.domain.assessment.AssessmentOrigin.PERIODIC_REVIEW);
    // A trilha diz por que era devido: nível e prazo aplicados.
    assertThat(captor.getValue().originDetail()).isEqualTo("CRITICAL@183d");
  }

  /** A política é quem decide: candidato do pré-filtro cujo nível ainda não venceu não é reavaliado. */
  @Test
  void candidato_que_a_politica_recusa_nao_gera_avaliacao() {
    SubjectRiskState naoDevido = corrente(RiskLevel.LOW, 400);
    when(riskState.findDueForPeriodicReview(any(), anyInt())).thenReturn(List.of(naoDevido));
    when(assessments.existsPendingBySubject(any(), anyString())).thenReturn(false);
    when(policy.decide(any(), anyString(), any(), any(), anyBoolean()))
        .thenReturn(
            ReassessmentDecision.nao(
                naoDevido.subjectId(),
                TENANT,
                ReassessmentTrigger.PERIODIC,
                null,
                ReassessmentDecision.INTERVALO_MINIMO,
                RiskLevel.LOW));

    assertThat(job.reassessDue()).isZero();
    verify(assessments, never()).submit(any());
  }

  /** Avaliação em análise vai concluir e atualizar a projeção — outra seria pagar duas vezes. */
  @Test
  void cliente_com_avaliacao_pendente_e_pulado_sem_consultar_a_politica() {
    SubjectRiskState devido = corrente(RiskLevel.HIGH, 400);
    when(riskState.findDueForPeriodicReview(any(), anyInt())).thenReturn(List.of(devido));
    when(assessments.existsPendingBySubject(any(), anyString())).thenReturn(true);

    assertThat(job.reassessDue()).isZero();
    verify(policy, never()).decide(any(), anyString(), any(), any(), anyBoolean());
    verify(assessments, never()).submit(any());
  }

  /** Falha de um cliente não interrompe os demais. */
  @Test
  void falha_em_um_cliente_nao_impede_os_outros() {
    SubjectRiskState a = corrente(RiskLevel.CRITICAL, 400);
    SubjectRiskState b = corrente(RiskLevel.CRITICAL, 500);
    when(riskState.findDueForPeriodicReview(any(), anyInt())).thenReturn(List.of(a, b));
    when(assessments.existsPendingBySubject(any(), anyString()))
        .thenThrow(new IllegalStateException("banco instável"))
        .thenReturn(false);
    permite(b);

    assertThat(job.reassessDue()).isEqualTo(1);
  }

  /** Frente que submete avaliação em massa não roda por acidente. */
  @Test
  void desligado_por_padrao_nao_varre_nada() {
    var desligado =
        new PeriodicReassessmentJob(riskState, policy, assessments, subjects, false, 200);

    desligado.run();

    verify(riskState, never()).findDueForPeriodicReview(any(), anyInt());
  }

  /** O teto vira o limite da consulta: o acúmulo é drenado ao longo de dias, não de uma vez. */
  @Test
  void teto_por_execucao_e_repassado_a_consulta() {
    var limitado = new PeriodicReassessmentJob(riskState, policy, assessments, subjects, true, 7);
    when(riskState.findDueForPeriodicReview(any(), anyInt())).thenReturn(List.of());

    limitado.reassessDue();

    verify(riskState).findDueForPeriodicReview(ReassessmentPolicy.menorIntervalo(), 7);
  }

  /** O pré-filtro é o menor prazo da tabela do ADR-0019 — hoje o do CRITICAL. */
  @Test
  void prefiltro_usa_o_menor_intervalo_da_politica() {
    assertThat(ReassessmentPolicy.menorIntervalo()).isEqualTo(Duration.ofDays(183));
  }

  private void permite(SubjectRiskState corrente) {
    when(policy.decide(any(), anyString(), any(), any(), anyBoolean()))
        .thenReturn(
            ReassessmentDecision.sim(
                corrente.subjectId(), TENANT, ReassessmentTrigger.PERIODIC, null, corrente.level()));
  }

  private static SubjectRiskState corrente(RiskLevel nivel, int diasAtras) {
    Instant quando = Instant.now().minus(diasAtras, ChronoUnit.DAYS);
    return new SubjectRiskState(
        UUID.randomUUID(),
        TENANT,
        nivel,
        500,
        AssessmentStatus.APROVADO,
        UUID.randomUUID(),
        "motor/1.0",
        quando,
        quando);
  }
}
