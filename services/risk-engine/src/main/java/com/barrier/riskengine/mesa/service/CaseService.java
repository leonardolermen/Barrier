package com.barrier.riskengine.mesa.service;

import com.barrier.riskengine.mesa.domain.AssessmentCase;
import com.barrier.riskengine.mesa.domain.CaseAction;
import com.barrier.riskengine.mesa.domain.CaseActionType;
import com.barrier.riskengine.mesa.domain.CaseQueue;
import com.barrier.riskengine.mesa.domain.SlaClock;
import com.barrier.riskengine.mesa.repository.interfaces.AssessmentCaseRepository;
import com.barrier.riskengine.mesa.repository.interfaces.CaseActionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso da mesa: fila, atribuição, ações manuais e SLA.
 *
 * <p>Tudo é escopado por tenant, e não existe assinatura sem {@code tenantId} — caso de um parceiro
 * não pode ser lido nem movido por outro. Mesma defesa por tipo do cadastro (ADR-0012).
 *
 * <p><b>O SLA não é uma coluna.</b> É reconstruído da linha do tempo de ações a cada leitura
 * ({@link SlaClock}). Contador incremental se perde no primeiro reprocessamento e não tem como ser
 * auditado depois; a lista de ações é a evidência, e o número é derivado dela.
 */
@Service
public class CaseService {

  private final AssessmentCaseRepository cases;
  private final CaseActionRepository actions;

  public CaseService(AssessmentCaseRepository cases, CaseActionRepository actions) {
    this.cases = cases;
    this.actions = actions;
  }

  /** Abre o caso quando a avaliação entra em revisão. Idempotente: reabrir não duplica. */
  @Transactional
  public AssessmentCase open(UUID assessmentId, String tenantId, CaseQueue queue) {
    return cases
        .find(assessmentId, tenantId)
        .orElseGet(() -> cases.save(AssessmentCase.open(assessmentId, tenantId, queue)));
  }

  @Transactional
  public AssessmentCase assign(UUID assessmentId, String tenantId, String analyst) {
    AssessmentCase caso = require(assessmentId, tenantId);
    actions.append(
        CaseAction.of(assessmentId, tenantId, CaseActionType.ASSIGNED, analyst, null));
    return cases.save(caso.assignTo(analyst));
  }

  @Transactional
  public AssessmentCase move(UUID assessmentId, String tenantId, CaseQueue destino, String actor) {
    AssessmentCase caso = require(assessmentId, tenantId);
    actions.append(
        CaseAction.of(
            assessmentId,
            tenantId,
            CaseActionType.MOVED,
            actor,
            caso.queue().name() + " -> " + destino.name()));
    return cases.save(caso.moveTo(destino));
  }

  /**
   * Pede documento ao parceiro e move o caso para {@code AGUARDANDO_PARCEIRO}.
   *
   * <p>Abre uma janela de espera <b>candidata</b> a pausa de SLA — ela só vira desconto quando o
   * recebimento for registrado. Ver {@link SlaClock}.
   */
  @Transactional
  public AssessmentCase requestDocument(
      UUID assessmentId, String tenantId, String actor, String detail) {
    AssessmentCase caso = require(assessmentId, tenantId);
    actions.append(
        CaseAction.of(assessmentId, tenantId, CaseActionType.DOCUMENT_REQUESTED, actor, detail));
    return cases.save(caso.moveTo(CaseQueue.AGUARDANDO_PARCEIRO));
  }

  /** Registra o recebimento e devolve o caso para a fila de análise — fecha a janela de espera. */
  @Transactional
  public AssessmentCase receiveDocument(
      UUID assessmentId, String tenantId, String actor, String detail) {
    AssessmentCase caso = require(assessmentId, tenantId);
    actions.append(
        CaseAction.of(assessmentId, tenantId, CaseActionType.DOCUMENT_RECEIVED, actor, detail));
    return cases.save(caso.moveTo(CaseQueue.ANALISE_PADRAO));
  }

  @Transactional
  public void note(UUID assessmentId, String tenantId, String actor, String text) {
    require(assessmentId, tenantId);
    actions.append(CaseAction.of(assessmentId, tenantId, CaseActionType.NOTE, actor, text));
  }

  /**
   * Fecha o caso ao registrar a decisão humana. Silencioso quando não há caso: a decisão pode ser
   * tomada por quem nunca abriu caso na mesa (ver {@code MesaCaseRouter}), e recusar aí bloquearia
   * o fluxo de decisão que já existe e funciona.
   */
  @Transactional
  public void close(UUID assessmentId, String tenantId, String actor, String detail) {
    cases
        .find(assessmentId, tenantId)
        .filter(AssessmentCase::isOpen)
        .ifPresent(
            caso -> {
              actions.append(
                  CaseAction.of(assessmentId, tenantId, CaseActionType.DECIDED, actor, detail));
              cases.save(caso.close());
            });
  }

  /** O caso, escopado por tenant. Vazio quando não existe ou é de outro parceiro. */
  @Transactional(readOnly = true)
  public java.util.Optional<AssessmentCase> find(UUID assessmentId, String tenantId) {
    return cases.find(assessmentId, tenantId);
  }

  @Transactional(readOnly = true)
  public List<AssessmentCase> queue(String tenantId, CaseQueue queue, int limit) {
    return cases.findOpenByQueue(tenantId, queue, limit);
  }

  @Transactional(readOnly = true)
  public List<CaseAction> timeline(UUID assessmentId, String tenantId) {
    require(assessmentId, tenantId);
    return actions.findByCase(assessmentId, tenantId);
  }

  /** Tempo consumido pela mesa, já descontada a espera comprovável do parceiro. */
  @Transactional(readOnly = true)
  public Duration sla(UUID assessmentId, String tenantId) {
    AssessmentCase caso = require(assessmentId, tenantId);
    return SlaClock.elapsed(
        caso.openedAt(), caso.closedAt(), actions.findByCase(assessmentId, tenantId), Instant.now());
  }

  private AssessmentCase require(UUID assessmentId, String tenantId) {
    return cases
        .find(assessmentId, tenantId)
        .orElseThrow(() -> new NoSuchElementException("Caso não encontrado: " + assessmentId));
  }
}
