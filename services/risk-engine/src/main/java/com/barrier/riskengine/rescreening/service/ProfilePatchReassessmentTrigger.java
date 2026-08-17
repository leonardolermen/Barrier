package com.barrier.riskengine.rescreening.service;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentOrigin;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import com.barrier.riskengine.rescreening.policy.domain.ReassessmentDecision;
import com.barrier.riskengine.rescreening.policy.domain.ReassessmentTrigger;
import com.barrier.riskengine.rescreening.policy.service.ReassessmentPolicy;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.profile.service.SubjectProfileUpdatedListener;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reavalia o cliente quando o parceiro altera o cadastro de forma material (ADR-0019).
 *
 * <p><b>Decisão de produto (2026-08-15): patch cadastral reavalia.</b> O gatilho
 * {@code PROFILE_PATCH} fura o intervalo mínimo — com o intervalo valendo, um cliente LOW só seria
 * reavaliado depois de 1095 dias e a política não mudaria nada na prática. O freio passa a ser
 * inteiramente a <b>materialidade</b>, apurada campo a campo por {@code MaterialProfileChange}:
 * reenviar o mesmo endereço não é alteração, e sem essa comparação todo {@code PUT} idempotente de
 * um parceiro que sincroniza cadastro em lote viraria uma consulta paga de bureau por cliente.
 *
 * <p>Mora em {@code rescreening} pelo mesmo motivo que {@code AssuranceReassessmentTrigger}:
 * {@code subject.profile} declara {@link SubjectProfileUpdatedListener} sem saber quem implementa,
 * porque chamar {@code AssessmentService} de dentro de {@code subject} fecharia o ciclo
 * {@code subject → assessment → subject}.
 *
 * <p><b>O laço que não existe:</b> o enriquecimento do cadastro pelo bureau, feito no meio do
 * processamento de uma avaliação, usa {@code SubjectProfileService.enrichFromBureau}, que não
 * notifica ninguém. Se usasse o mesmo caminho do parceiro, toda avaliação geraria outra avaliação,
 * cada uma com sua consulta paga, indefinidamente.
 */
@Service
public class ProfilePatchReassessmentTrigger implements SubjectProfileUpdatedListener {

  private static final Logger log =
      LoggerFactory.getLogger(ProfilePatchReassessmentTrigger.class);

  private final AssessmentService assessments;
  private final SubjectService subjects;
  private final ReassessmentPolicy policy;
  private final Duration window;

  public ProfilePatchReassessmentTrigger(
      AssessmentService assessments,
      SubjectService subjects,
      ReassessmentPolicy policy,
      @Value("${barrier.subject.profile.reassessment-window:PT5M}") Duration window) {
    this.assessments = assessments;
    this.subjects = subjects;
    this.policy = policy;
    this.window = window;
  }

  /**
   * {@code REQUIRES_NEW} pelo mesmo motivo do gatilho de assurance: quem chama é um
   * {@code afterCommit}, onde a transação que acabou de commitar ainda está ligada à thread — sem
   * propagation própria, a avaliação e a linha do outbox não teriam transação viva para commitar e
   * sumiriam sem lançar.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void onMaterialChange(UUID subjectId, String tenantId, Set<String> changedFields) {
    try {
      // Avaliação já em análise absorve a mudança sozinha: ela lê o cadastro quando for processada,
      // e vai enxergar o valor novo. Sem esta trava, o fluxo NORMAL de onboarding — POST da
      // avaliação e depois PUT do cadastro, que é como o parceiro completa a CMN 4.753 — criaria
      // uma segunda avaliação para todo cliente novo, dobrando a consulta paga de bureau no
      // caminho mais comum que existe.
      if (assessments.existsPendingBySubject(subjectId, tenantId)) {
        log.debug(
            "Alteração de cadastro do subject {} absorvida pela avaliação já em análise", subjectId);
        return;
      }
      // Dedup por janela curta, mesmo vocabulário do gatilho de assurance: um parceiro que salva o
      // formulário campo a campo dispararia uma avaliação por tecla.
      if (assessments.existsRecentByOriginAndSubject(
          subjectId, tenantId, AssessmentOrigin.PROFILE_PATCH, window)) {
        log.info(
            "Reavaliação por cadastro suprimida (dedup): subject {} tenant {} já tem avaliação"
                + " PROFILE_PATCH nos últimos {}. O cadastro foi gravado normalmente.",
            subjectId,
            tenantId,
            window);
        return;
      }

      String detail = String.join(",", changedFields);
      ReassessmentDecision decision =
          policy.decide(subjectId, tenantId, ReassessmentTrigger.PROFILE_PATCH, detail, true);
      if (!decision.reassess()) {
        return;
      }

      Subject subject = subjects.findById(subjectId, tenantId);
      assessments.submit(
          SubmitAssessmentCommand.profilePatch(
              tenantId,
              DocumentType.valueOf(subject.documentType()),
              subject.document(),
              subject.name(),
              detail));
    } catch (RuntimeException e) {
      // O cadastro já foi gravado e continua válido; falhar aqui não pode desfazê-lo.
      log.error("Reavaliação por alteração de cadastro falhou para o subject {}", subjectId, e);
    }
  }
}
