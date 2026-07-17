package com.barrier.riskengine.history.controller;

import com.barrier.riskengine.history.service.SubjectHistoryService;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import com.barrier.riskengine.tenant.domain.Tenant;
import com.barrier.riskengine.tenant.service.TenantService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Histórico interno (chargeback, PIX devolvido, denúncia, conta encerrada por fraude) — operação
 * interna/admin (compliance/atendimento registra o evento; hoje não há pipeline automático de
 * transação/PIX alimentando isto, ver Fase 8 item 7). Mesma pré-auth por header do resto da API.
 */
@RestController
@RequestMapping("/v1/subjects/{document}/history")
public class SubjectHistoryController {

  private static final String CLIENT_HEADER = "X-Client-Id";

  private final SubjectService subjectService;
  private final SubjectHistoryService historyService;
  private final TenantService tenantService;

  public SubjectHistoryController(
      SubjectService subjectService,
      SubjectHistoryService historyService,
      TenantService tenantService) {
    this.subjectService = subjectService;
    this.historyService = historyService;
    this.tenantService = tenantService;
  }

  @PostMapping
  public ResponseEntity<HistoryEventResponse> record(
      @RequestHeader(name = CLIENT_HEADER, required = false) String clientId,
      @PathVariable String document,
      @Valid @RequestBody RecordHistoryEventRequest request) {
    Subject subject = resolveSubject(clientId, document);
    var saved =
        historyService.record(
            subject.id(), request.eventType(), request.detail(), request.occurredAt());
    return ResponseEntity.ok(HistoryEventResponse.of(saved));
  }

  @GetMapping
  public ResponseEntity<List<HistoryEventResponse>> list(
      @RequestHeader(name = CLIENT_HEADER, required = false) String clientId,
      @PathVariable String document) {
    Subject subject = resolveSubject(clientId, document);
    return ResponseEntity.ok(
        historyService.findBySubjectId(subject.id()).stream()
            .map(HistoryEventResponse::of)
            .toList());
  }

  private Subject resolveSubject(String clientId, String document) {
    Tenant tenant = tenantService.resolve(clientId);
    String digits = document.replaceAll("\\D", "");
    String documentType =
        switch (digits.length()) {
          case 11 -> "CPF";
          case 14 -> "CNPJ";
          default -> throw new IllegalArgumentException("Documento inválido");
        };
    return subjectService.getForTenant(tenant.id(), documentType, digits);
  }
}
