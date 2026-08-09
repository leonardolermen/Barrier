package com.barrier.riskengine.tenant.controller;

import com.barrier.riskengine.tenant.domain.ApiKeyMaterial;
import com.barrier.riskengine.tenant.service.ApiKeyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emissão de credenciais de tenant. Operação administrativa — protegida por {@code X-Admin-Key}
 * ({@code AdminApiKeyFilter}), nunca self-service: emitir a própria credencial permitiria a
 * qualquer um virar qualquer tenant.
 *
 * <p>O valor em claro aparece <b>uma única vez</b>, nesta resposta. Não há endpoint que o recupere,
 * porque o banco guarda só o hash — perdeu, emite outra.
 */
@RestController
@RequestMapping("/v1/tenants/{tenantId}/api-keys")
public class TenantApiKeyController {

  private final ApiKeyService apiKeyService;

  public TenantApiKeyController(ApiKeyService apiKeyService) {
    this.apiKeyService = apiKeyService;
  }

  @PostMapping
  public ResponseEntity<IssuedApiKeyResponse> issue(
      @PathVariable String tenantId, @RequestBody IssueApiKeyRequest request) {
    ApiKeyMaterial.Generated generated = apiKeyService.issue(tenantId, request.name());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new IssuedApiKeyResponse(
                generated.keyId(),
                generated.presentedValue(),
                "Guarde agora: este valor não é recuperável."));
  }
}
