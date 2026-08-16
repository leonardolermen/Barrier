package com.barrier.riskengine.mesa.controller;

import com.barrier.riskengine.mesa.controller.dto.CaseDtos;
import com.barrier.riskengine.mesa.domain.AssessmentCase;
import com.barrier.riskengine.mesa.domain.CaseQueue;
import com.barrier.riskengine.mesa.service.CaseService;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mesa de análise: fila, atribuição e ações manuais.
 *
 * <p>A decisão em si continua no {@code POST /v1/assessments/{id}/decision} — não foi duplicada
 * aqui. Este controller cuida do <b>trabalho em torno</b> da decisão, que é o que faltava: fila,
 * quem pegou, o que foi pedido e há quanto tempo.
 */
@RestController
@RequestMapping("/v1/mesa")
public class CaseController {

  private final CaseService cases;

  public CaseController(CaseService cases) {
    this.cases = cases;
  }

  /** Fila de trabalho, mais antigo primeiro. */
  @GetMapping("/queues/{queue}")
  public ResponseEntity<List<CaseDtos.CaseSummary>> queue(
      AuthenticatedTenant tenant,
      @PathVariable String queue,
      @RequestParam(defaultValue = "50") int limit) {
    List<AssessmentCase> casos =
        cases.queue(tenant.id(), CaseQueue.valueOf(queue.toUpperCase()), Math.min(limit, 200));
    return ResponseEntity.ok(casos.stream().map(caso -> summary(tenant.id(), caso)).toList());
  }

  /** Linha do tempo do caso — a evidência de que o SLA é derivado, não acumulado. */
  @GetMapping("/cases/{assessmentId}")
  public ResponseEntity<CaseDtos.CaseTimeline> timeline(
      AuthenticatedTenant tenant, @PathVariable UUID assessmentId) {
    List<CaseDtos.ActionEntry> acoes =
        cases.timeline(assessmentId, tenant.id()).stream()
            .map(
                a ->
                    new CaseDtos.ActionEntry(
                        a.type().name(), a.actor(), a.detail(), a.occurredAt()))
            .toList();
    AssessmentCase caso = cases.find(assessmentId, tenant.id()).orElseThrow();
    return ResponseEntity.ok(new CaseDtos.CaseTimeline(summary(tenant.id(), caso), acoes));
  }

  @PostMapping("/cases/{assessmentId}/assign")
  public ResponseEntity<CaseDtos.CaseSummary> assign(
      AuthenticatedTenant tenant,
      @PathVariable UUID assessmentId,
      @Valid @RequestBody CaseDtos.AssignRequest request) {
    AssessmentCase caso = cases.assign(assessmentId, tenant.id(), request.analyst());
    return ResponseEntity.ok(summary(tenant.id(), caso));
  }

  @PostMapping("/cases/{assessmentId}/move")
  public ResponseEntity<CaseDtos.CaseSummary> move(
      AuthenticatedTenant tenant,
      @PathVariable UUID assessmentId,
      @Valid @RequestBody CaseDtos.MoveRequest request) {
    AssessmentCase caso =
        cases.move(
            assessmentId,
            tenant.id(),
            CaseQueue.valueOf(request.queue().toUpperCase()),
            request.actor());
    return ResponseEntity.ok(summary(tenant.id(), caso));
  }

  /** Move para {@code AGUARDANDO_PARCEIRO} e abre a janela de espera candidata a pausa de SLA. */
  @PostMapping("/cases/{assessmentId}/request-document")
  public ResponseEntity<CaseDtos.CaseSummary> requestDocument(
      AuthenticatedTenant tenant,
      @PathVariable UUID assessmentId,
      @Valid @RequestBody CaseDtos.DocumentRequest request) {
    AssessmentCase caso =
        cases.requestDocument(assessmentId, tenant.id(), request.actor(), request.detail());
    return ResponseEntity.ok(summary(tenant.id(), caso));
  }

  /** Fecha a janela de espera: só a partir daqui o intervalo vira desconto de SLA. */
  @PostMapping("/cases/{assessmentId}/receive-document")
  public ResponseEntity<CaseDtos.CaseSummary> receiveDocument(
      AuthenticatedTenant tenant,
      @PathVariable UUID assessmentId,
      @Valid @RequestBody CaseDtos.DocumentRequest request) {
    AssessmentCase caso =
        cases.receiveDocument(assessmentId, tenant.id(), request.actor(), request.detail());
    return ResponseEntity.ok(summary(tenant.id(), caso));
  }

  @PostMapping("/cases/{assessmentId}/notes")
  public ResponseEntity<Void> note(
      AuthenticatedTenant tenant,
      @PathVariable UUID assessmentId,
      @Valid @RequestBody CaseDtos.NoteRequest request) {
    cases.note(assessmentId, tenant.id(), request.actor(), request.text());
    return ResponseEntity.accepted().build();
  }

  private CaseDtos.CaseSummary summary(String tenantId, AssessmentCase caso) {
    return new CaseDtos.CaseSummary(
        caso.assessmentId().toString(),
        caso.queue().name(),
        caso.assignedTo(),
        caso.openedAt(),
        caso.closedAt(),
        cases.sla(caso.assessmentId(), tenantId).toSeconds());
  }
}
