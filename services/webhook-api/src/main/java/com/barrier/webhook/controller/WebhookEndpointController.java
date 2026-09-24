package com.barrier.webhook.controller;

import com.barrier.webhook.controller.dto.WebhookEndpointSecretResponse;
import com.barrier.webhook.controller.dto.WebhookEndpointResponse;
import com.barrier.webhook.controller.dto.RegisterEndpointRequest;
import com.barrier.webhookdelivery.domain.WebhookEndpoint;
import com.barrier.webhookdelivery.service.WebhookEndpointService;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro do endpoint de callback de cada tenant. Operação <b>administrativa</b>, protegida por
 * {@code X-Admin-Key}: quem escreve aqui decide para onde vai o resultado de KYC de um parceiro, e
 * deixar o próprio tenant apontar o destino seria self-service para redirecionar callback alheio.
 *
 * <p>O contrato daqui é por {@code tenantId} — um endpoint por tenant, inscrito em tudo — e não
 * conhece {@code id} nem filtro por evento; isso é o que {@link WebhookEndpointService#registerSingle}
 * preserva na lib, que por sua vez sabe lidar com N endpoints por tenant. As demais operações
 * resolvem o endpoint único do tenant (o mais antigo) e chamam a versão por {@code id} da lib.
 */
@RestController
@RequestMapping("/v1/webhook-endpoints")
public class WebhookEndpointController {

  private final WebhookEndpointService service;

  public WebhookEndpointController(WebhookEndpointService service) {
    this.service = service;
  }

  /**
   * Registra ou atualiza o destino de um tenant. Registro novo nasce com segredo HMAC próprio,
   * devolvido <b>uma única vez</b>; atualizar a URL de um tenant já registrado preserva o segredo
   * dele — trocar de quebra derrubaria a verificação do cliente.
   */
  @PutMapping("/{tenantId}")
  public ResponseEntity<WebhookEndpointSecretResponse> register(
      @PathVariable String tenantId, @RequestBody RegisterEndpointRequest request) {
    WebhookEndpoint endpoint = service.registerSingle(tenantId, request.targetUrl());
    return ResponseEntity.ok(WebhookEndpointSecretResponse.from(endpoint));
  }

  /**
   * Rotaciona o segredo do tenant. O anterior segue aceito pela janela de sobreposição, e durante
   * ela cada entrega leva as duas assinaturas — o parceiro troca a chave quando puder.
   */
  @PostMapping("/{tenantId}/rotate-secret")
  public ResponseEntity<WebhookEndpointSecretResponse> rotateSecret(@PathVariable String tenantId) {
    return endpointUnico(tenantId)
        .flatMap(e -> service.rotateSecret(e.id()))
        .map(WebhookEndpointSecretResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping
  public List<WebhookEndpointResponse> list() {
    return service.listAll().stream().map(WebhookEndpointResponse::from).toList();
  }

  @GetMapping("/{tenantId}")
  public ResponseEntity<WebhookEndpointResponse> get(@PathVariable String tenantId) {
    return endpointUnico(tenantId)
        .map(WebhookEndpointResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Desativa a entrega para o tenant. Não apaga a linha: o registro de que o endpoint existiu é o
   * que se consulta quando um cliente reclama de callback não recebido.
   */
  @DeleteMapping("/{tenantId}")
  public ResponseEntity<WebhookEndpointResponse> deactivate(@PathVariable String tenantId) {
    return endpointUnico(tenantId)
        .flatMap(e -> service.deactivate(e.id()))
        .map(WebhookEndpointResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** O contrato do Barrier é "um endpoint por tenant" — o mais antigo dos que a lib guarda para ele. */
  private Optional<WebhookEndpoint> endpointUnico(String tenantId) {
    return service.listByTenant(tenantId).stream().findFirst();
  }

  /** URL inválida (sem TLS, esquema estranho, vazia) é erro do chamador, não do servidor. */
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail handleInvalid(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }
}
