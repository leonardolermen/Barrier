package com.barrier.riskengine.subject.controller;

import com.barrier.riskengine.subject.controller.dto.SubjectResponse;

import com.barrier.commons.mask.Documents;
import com.barrier.riskengine.subject.domain.DocumentTypeResolver;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta de subjects (clientes finais) escopada por tenant. Um subject que o tenant não
 * conhece responde 404 — evita descobrir clientes de outras empresas.
 */
@RestController
@RequestMapping("/v1/subjects")
public class SubjectController {

  private final SubjectService subjectService;

  public SubjectController(SubjectService subjectService) {
    this.subjectService = subjectService;
  }

  /** Busca um subject pelo documento (CPF de 11 ou CNPJ de 14 dígitos). */
  @GetMapping("/{document}")
  public ResponseEntity<SubjectResponse> get(
      AuthenticatedTenant tenant, @PathVariable String document) {
    DocumentTypeResolver.Resolved resolved = DocumentTypeResolver.resolve(document);
    Subject subject =
        subjectService.getForTenant(tenant.id(), resolved.documentType(), resolved.digits());
    return ResponseEntity.ok(
        new SubjectResponse(
            subject.id().toString(),
            subject.documentType(),
            Documents.mask(subject.document()),
            subject.name()));
  }
}
