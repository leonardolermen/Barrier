package com.barrier.riskengine.subject.controller;

import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import com.barrier.riskengine.tenant.domain.Tenant;
import com.barrier.riskengine.tenant.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta de subjects (clientes finais) escopada por tenant. Um subject que o tenant não
 * conhece responde 404 — evita descobrir clientes de outras empresas.
 */
@RestController
@RequestMapping("/v1/subjects")
public class SubjectController {

  private static final String CLIENT_HEADER = "X-Client-Id";

  private final SubjectService subjectService;
  private final TenantService tenantService;

  public SubjectController(SubjectService subjectService, TenantService tenantService) {
    this.subjectService = subjectService;
    this.tenantService = tenantService;
  }

  /** Busca um subject pelo documento (CPF de 11 ou CNPJ de 14 dígitos). */
  @GetMapping("/{document}")
  public ResponseEntity<SubjectResponse> get(
      @RequestHeader(name = CLIENT_HEADER, required = false) String clientId,
      @PathVariable String document) {
    Tenant tenant = tenantService.resolve(clientId);
    String digits = document.replaceAll("\\D", "");
    String documentType =
        switch (digits.length()) {
          case 11 -> "CPF";
          case 14 -> "CNPJ";
          default -> throw new IllegalArgumentException("Documento inválido");
        };
    Subject subject = subjectService.getForTenant(tenant.id(), documentType, digits);
    return ResponseEntity.ok(
        new SubjectResponse(
            subject.id().toString(),
            subject.documentType(),
            mask(subject.document()),
            subject.name()));
  }

  /** Mascara o documento mantendo apenas os 2 últimos dígitos (minimização de dado). */
  private static String mask(String digits) {
    if (digits.length() <= 2) {
      return digits;
    }
    return "*".repeat(digits.length() - 2) + digits.substring(digits.length() - 2);
  }
}
