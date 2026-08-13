package com.barrier.riskengine.rescreening.service;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentOrigin;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.service.AssuranceRecordedListener;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reavalia o cliente quando uma verificação de documentoscopia/biometria é gravada.
 *
 * <p>Mora em {@code rescreening}, não em {@code assurance}, exatamente pelo mesmo motivo que o
 * monitoramento contínuo mora aqui e não em {@code screening}: {@code assurance} declara
 * {@link AssuranceRecordedListener} sem saber quem a implementa, porque implementá-la ali
 * chamando {@code AssessmentService} direto fecharia o ciclo {@code assurance → assessment} — e
 * {@code risk} já importa tanto {@code assurance} (via {@code IdentityAssuranceRiskRule}) quanto
 * (transitivamente) {@code assessment}. {@code rescreening → assessment} já é uma aresta
 * existente, então é aqui que a reação pode morar sem novo ciclo.
 *
 * <p><b>Não</b> reusa {@code RescreeningService.submit}: aquela assinatura recebe um
 * {@code MonitoredSubject}, que nasce de um match de watchlist — não descreve uma verificação de
 * documento. Este trigger chama {@code AssessmentService.submit} direto.
 *
 * <p>O {@code AssuranceCheck} só carrega {@code subjectId}; documento, tipo e nome para montar o
 * comando vêm do {@code Subject} resolvido por {@code SubjectService.findById(subjectId,
 * tenantId)}. O {@code tenantId} vem do próprio {@code check} (o tenant que pediu a verificação),
 * não é opcional: sem ele, um {@code subjectId} de outro parceiro resolveria documento e nome
 * alheios, e o {@code AssessmentService.submit} chamado a seguir criaria o vínculo tenant↔subject
 * e um {@code Assessment} vazando dado de cliente de outro tenant — exatamente o vazamento que
 * {@code SubjectService.findById} com escopo de tenant existe para impedir.
 *
 * <p><b>Dedup por janela curta</b> ({@code barrier.assurance.reassessment-window}, default
 * {@code PT5M}): no máximo uma reavaliação por {@code (subject, tenant)} a cada janela. Vinte
 * tentativas de biometria de um parceiro em sequência — retry automático de app, dedo no botão —
 * viravam vinte avaliações completas: vinte consultas pagas de bureau e vinte rodadas de
 * screening pelo mesmo evento. Mesmo vocabulário do {@code RescreeningService} ("uma avaliação
 * por (subject, tenant) por importação"), aqui por tempo em vez de por importação, porque não há
 * um lote análogo para amarrar a trava.
 *
 * <p><b>A submissão dentro da janela continua gravando o {@code AssuranceCheck} normalmente</b> —
 * o que se suprime é a reavaliação, não o registro. Deliberado: a trilha de tentativas é
 * justamente o sinal de fraude que {@code IdentityAssuranceRiskRule}/{@code
 * AssuranceService.attempts} contam (ver item 2 do plano de throttle) — perdê-la aqui seria pior
 * que o problema que esta janela resolve. A verificação é feita consultando avaliações existentes
 * com {@code origin = ASSURANCE} para aquele {@code (subject, tenant)} dentro da janela
 * ({@code AssessmentService.existsRecentByOriginAndSubject}), não uma tabela nova. A checagem
 * passa por {@code AssessmentService}, não pelo repositório de assessment direto: o service é o
 * portão do módulo — é onde vivem transação, escopo por tenant e invariantes — e este trigger já
 * depende dele para {@code submit}. Falar com o repositório alheio pularia esse portão e o
 * próximo gatilho copiaria o atalho.
 */
@Service
public class AssuranceReassessmentTrigger implements AssuranceRecordedListener {

  private static final Logger log = LoggerFactory.getLogger(AssuranceReassessmentTrigger.class);

  private final AssessmentService assessments;
  private final SubjectService subjects;
  private final Duration reassessmentWindow;

  public AssuranceReassessmentTrigger(
      AssessmentService assessments,
      SubjectService subjects,
      @Value("${barrier.assurance.reassessment-window:PT5M}") Duration reassessmentWindow) {
    this.assessments = assessments;
    this.subjects = subjects;
    this.reassessmentWindow = reassessmentWindow;
  }

  /**
   * Dispara em <b>qualquer</b> desfecho — PASS, FAIL, INCONCLUSIVE, UNAVAILABLE. Um FAIL de prova
   * de vida é o insumo que mais muda a decisão; disparar só no sucesso deixaria a avaliação parada
   * exatamente no caso de fraude.
   *
   * <p>Nunca lança: o {@code AssuranceService} já isola cada listener, mas o motivo de não deixar
   * escapar aqui também é próprio — uma falha ao reavaliar não pode contaminar a verificação de
   * assurance que já foi gravada com sucesso.
   *
   * <p><b>{@code REQUIRES_NEW}, não {@code REQUIRED}.</b> Quem chama {@code onRecorded} é o
   * {@code TransactionSynchronization.afterCommit()} registrado por
   * {@code AssuranceService.scheduleNotification} — e no {@code JpaTransactionManager} o
   * {@code EntityManagerHolder} da transação que acabou de commitar continua ligado à thread
   * durante o {@code afterCommit} (a limpeza só roda depois, em
   * {@code cleanupAfterCompletion}). Sem propagation própria, {@code AssessmentService.submit}
   * (que é {@code @Transactional} puro, ou seja {@code REQUIRED}) <b>entraria</b> nessa
   * transação já commitada em vez de abrir uma nova — o {@code Assessment}, o vínculo
   * tenant↔subject e a linha do outbox não teriam mais transação viva para commitar, e sumiriam
   * sem lançar (ou estourariam "transaction not active" no flush, dependendo do provider). Mesmo
   * motivo do {@code REQUIRES_NEW} em {@code IdempotencyService}: a operação precisa da própria
   * transação, não de carona na que já foi.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void onRecorded(AssuranceCheck check) {
    try {
      if (assessments.existsRecentByOriginAndSubject(
          check.subjectId(), check.tenantId(), AssessmentOrigin.ASSURANCE, reassessmentWindow)) {
        log.info(
            "Reavaliação por assurance suprimida (dedup): subject {} tenant {} já tem avaliação"
                + " ASSURANCE nos últimos {}. A verificação {} foi gravada normalmente; só a"
                + " reavaliação foi suprimida.",
            check.subjectId(),
            check.tenantId(),
            reassessmentWindow,
            check.id());
        return;
      }
      Subject subject = subjects.findById(check.subjectId(), check.tenantId());
      String originDetail = check.kind() + "@" + check.providerReference();
      assessments.submit(
          SubmitAssessmentCommand.assurance(
              check.tenantId(),
              documentType(subject),
              subject.document(),
              subject.name(),
              originDetail));
    } catch (RuntimeException e) {
      log.error(
          "Reavaliação por assurance falhou para o subject {} (check {}); verificação segue"
              + " válida",
          check.subjectId(),
          check.id(),
          e);
    }
  }

  /**
   * {@code Subject.documentType()} é {@code String} (o subject é global e não depende de
   * {@code assessment}); um valor fora do enum é erro de dado, não indisponibilidade do
   * reavaliador — a mensagem própria evita que ele se perca atrás do "reavaliação falhou" genérico
   * do catch acima quando alguém lê o log.
   */
  private DocumentType documentType(Subject subject) {
    try {
      return DocumentType.valueOf(subject.documentType());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "subject "
              + subject.id()
              + " com documentType inválido para reavaliação: "
              + subject.documentType(),
          e);
    }
  }
}
