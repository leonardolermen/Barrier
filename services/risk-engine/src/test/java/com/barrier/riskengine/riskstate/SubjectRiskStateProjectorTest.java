package com.barrier.riskengine.riskstate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.riskstate.domain.RiskLevelTransition;
import com.barrier.riskengine.riskstate.service.RiskLevelChangeEventPublisher;
import com.barrier.riskengine.riskstate.service.SubjectRiskStateProjector;
import com.barrier.riskengine.riskstate.service.SubjectRiskStateService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Quando o evento de mudança de nível sai — e, principalmente, quando ele <b>não</b> sai. Emitir
 * demais é pior que não emitir: o parceiro recebe ruído de madrugada e passa a ignorar o canal.
 */
@ExtendWith(MockitoExtension.class)
class SubjectRiskStateProjectorTest {

  @Mock SubjectRiskStateService service;
  @Mock RiskLevelChangeEventPublisher eventPublisher;
  @InjectMocks SubjectRiskStateProjector projector;

  @Test
  void transicao_de_nivel_publica_exatamente_um_evento() {
    Assessment assessment = concluida(RiskLevel.HIGH, AssessmentStatus.EM_REVISAO);
    RiskLevelTransition transition = new RiskLevelTransition(RiskLevel.LOW, RiskLevel.HIGH);
    when(service.record(assessment, 700, "motor/1.0")).thenReturn(Optional.of(transition));

    projector.onCompleted(assessment, 700, "motor/1.0");

    verify(eventPublisher).publish(assessment, transition, "motor/1.0");
  }

  @Test
  void avaliacao_sem_mudanca_de_nivel_nao_publica_nada() {
    Assessment assessment = concluida(RiskLevel.LOW, AssessmentStatus.APROVADO);
    when(service.record(assessment, 30, "motor/1.0")).thenReturn(Optional.empty());

    projector.onCompleted(assessment, 30, "motor/1.0");

    verify(eventPublisher, never()).publish(any(), any(), any());
  }

  /** Decisão humana muda o desfecho, não o nível apurado pelo motor — logo, não é mudança de nível. */
  @Test
  void decisao_manual_atualiza_a_projecao_sem_publicar_evento_de_nivel() {
    Assessment assessment = concluida(RiskLevel.MEDIUM, AssessmentStatus.APROVADO);
    when(service.recordManualDecision(assessment)).thenReturn(Optional.empty());

    projector.onCompleted(assessment, null, null);

    verify(service).recordManualDecision(assessment);
    verify(service, never()).record(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    verify(eventPublisher, never()).publish(any(), any(), any());
  }

  private static Assessment concluida(RiskLevel level, AssessmentStatus status) {
    Assessment assessment =
        Assessment.submit(
            "acme", UUID.randomUUID().toString(), DocumentType.CPF, "111.444.777-35", "Fulano");
    assessment.complete(level, status, "decisão", List.of());
    return assessment;
  }
}
