package com.barrier.riskengine.rescreening;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentOrigin;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import com.barrier.riskengine.rescreening.policy.domain.ReassessmentDecision;
import com.barrier.riskengine.rescreening.policy.domain.ReassessmentTrigger;
import com.barrier.riskengine.rescreening.policy.service.ReassessmentPolicy;
import com.barrier.riskengine.rescreening.service.ProfilePatchReassessmentTrigger;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.subject.domain.Subject;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** As travas de custo do gatilho de cadastro. */
@ExtendWith(MockitoExtension.class)
class ProfilePatchReassessmentTriggerTest {

  private static final UUID SUBJECT = UUID.randomUUID();
  private static final String TENANT = "acme";
  private static final Set<String> CAMPOS = Set.of("phone");

  @Mock AssessmentService assessments;
  @Mock com.barrier.riskengine.subject.service.SubjectService subjects;
  @Mock ReassessmentPolicy policy;

  private ProfilePatchReassessmentTrigger trigger;

  @BeforeEach
  void setUp() {
    trigger =
        new ProfilePatchReassessmentTrigger(assessments, subjects, policy, Duration.ofMinutes(5));
    lenient()
        .when(subjects.findById(SUBJECT, TENANT))
        .thenReturn(Subject.create("CPF", "11144477735", "Fulano"));
    lenient()
        .when(policy.decide(any(), anyString(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(
            ReassessmentDecision.sim(
                SUBJECT, TENANT, ReassessmentTrigger.PROFILE_PATCH, "phone", RiskLevel.LOW));
  }

  @Test
  void alteracao_material_com_cliente_sem_avaliacao_pendente_reavalia() {
    when(assessments.existsPendingBySubject(SUBJECT, TENANT)).thenReturn(false);
    when(assessments.existsRecentByOriginAndSubject(
            SUBJECT, TENANT, AssessmentOrigin.PROFILE_PATCH, Duration.ofMinutes(5)))
        .thenReturn(false);

    trigger.onMaterialChange(SUBJECT, TENANT, CAMPOS);

    verify(assessments).submit(any(SubmitAssessmentCommand.class));
  }

  /**
   * O fluxo normal de onboarding: POST da avaliação e depois PUT do cadastro. A avaliação que já
   * está na fila lê o cadastro quando for processada — criar outra dobraria a consulta paga de
   * bureau para todo cliente novo.
   */
  @Test
  void avaliacao_ja_em_analise_absorve_a_mudanca_sem_criar_outra() {
    when(assessments.existsPendingBySubject(SUBJECT, TENANT)).thenReturn(true);

    trigger.onMaterialChange(SUBJECT, TENANT, CAMPOS);

    verify(assessments, never()).submit(any());
    verify(policy, never()).decide(any(), anyString(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  /** Formulário salvo campo a campo não pode virar uma avaliação por tecla. */
  @Test
  void segunda_alteracao_dentro_da_janela_nao_reavalia() {
    when(assessments.existsPendingBySubject(SUBJECT, TENANT)).thenReturn(false);
    when(assessments.existsRecentByOriginAndSubject(
            SUBJECT, TENANT, AssessmentOrigin.PROFILE_PATCH, Duration.ofMinutes(5)))
        .thenReturn(true);

    trigger.onMaterialChange(SUBJECT, TENANT, CAMPOS);

    verify(assessments, never()).submit(any());
  }

  /** Falha ao reavaliar não pode invalidar o cadastro que já foi gravado. */
  @Test
  void falha_ao_reavaliar_nao_propaga() {
    when(assessments.existsPendingBySubject(SUBJECT, TENANT)).thenReturn(false);
    when(assessments.existsRecentByOriginAndSubject(any(), anyString(), any(), any()))
        .thenThrow(new IllegalStateException("banco fora do ar"));

    trigger.onMaterialChange(SUBJECT, TENANT, CAMPOS);
  }
}
