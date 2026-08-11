package com.barrier.webhook.service;

import com.barrier.webhook.config.WebhookProperties;
import com.barrier.webhook.domain.SigningMaterial;
import com.barrier.webhook.domain.WebhookEndpoint;
import com.barrier.webhook.repository.WebhookEndpointRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registro dos endpoints de callback e resolução do destino de uma entrega. */
@Service
public class WebhookEndpointService {

  private static final Logger log = LoggerFactory.getLogger(WebhookEndpointService.class);

  private final WebhookEndpointRepository repository;
  private final WebhookProperties properties;
  private final Duration rotationOverlap;

  public WebhookEndpointService(
      WebhookEndpointRepository repository,
      WebhookProperties properties,
      @Value("${barrier.webhook.secret-rotation-overlap:PT24H}") Duration rotationOverlap) {
    this.repository = repository;
    this.properties = properties;
    this.rotationOverlap = rotationOverlap;
  }

  /**
   * Destino da entrega para o tenant dono do evento.
   *
   * <p>O registro por tenant é a única fonte que endereça corretamente. O
   * {@code barrier.webhook.target-url} global segue aceito como <b>fallback de desenvolvimento</b>,
   * onde há um único destino e nenhum dado real; em produção ele é proibido pelo
   * {@code GlobalTargetUrlReadinessGuard}, justamente porque com dois tenants ele entrega as
   * decisões de um no endpoint do outro.
   *
   * <p>Sem registro e sem fallback, devolve vazio: a entrega não acontece. Não entregar é o
   * desfecho correto — entregar no lugar errado é irreversível, e a decisão continua registrada na
   * risk-engine, disponível pelo {@code GET /v1/assessments/{id}}.
   */
  @Transactional(readOnly = true)
  public Optional<String> resolveTargetUrl(String tenantId) {
    if (tenantId != null && !tenantId.isBlank()) {
      Optional<WebhookEndpoint> registered = repository.findByTenantId(tenantId);
      if (registered.isPresent()) {
        WebhookEndpoint endpoint = registered.get();
        if (!endpoint.active()) {
          log.warn("Endpoint do tenant {} está desativado; nada será entregue", tenantId);
          return Optional.empty();
        }
        return Optional.of(endpoint.targetUrl());
      }
    }
    String global = properties.targetUrl();
    if (global == null || global.isBlank()) {
      return Optional.empty();
    }
    log.warn(
        "Tenant {} sem endpoint registrado; usando o destino global (aceitável só fora de produção)",
        tenantId);
    return Optional.of(global);
  }

  /**
   * Registra o destino do tenant. Um tenant já registrado <b>mantém o segredo</b>: isto aqui é
   * atualização de URL, e trocar o segredo de quebra derrubaria a verificação do cliente sem
   * ninguém ter pedido rotação — para isso existe {@link #rotateSecret(String)}.
   */
  @Transactional
  public WebhookEndpoint register(String tenantId, String targetUrl) {
    String segredoAtual =
        repository.findByTenantId(tenantId).map(WebhookEndpoint::secret).orElse(null);
    WebhookEndpoint endpoint =
        segredoAtual == null
            ? WebhookEndpoint.register(tenantId, targetUrl)
            : WebhookEndpoint.register(tenantId, targetUrl, segredoAtual);
    WebhookEndpoint salvo = repository.save(endpoint);
    log.info(
        "Endpoint de webhook do tenant {} registrado (segredo {})",
        tenantId,
        segredoAtual == null ? "novo" : "preservado");
    return salvo;
  }

  /**
   * Gera um segredo novo para o tenant, mantendo o anterior válido pela janela de sobreposição.
   *
   * <p>Durante a janela, cada entrega leva as duas assinaturas — o cliente troca a chave quando
   * puder. É o que faz rotação deixar de ser um evento combinado a dedo com cada parceiro.
   */
  @Transactional
  public Optional<WebhookEndpoint> rotateSecret(String tenantId) {
    Optional<WebhookEndpoint> rotacionado =
        repository
            .findByTenantId(tenantId)
            .map(e -> e.rotateSecret(rotationOverlap))
            .map(repository::save);
    rotacionado.ifPresent(
        e ->
            log.info(
                "Segredo do tenant {} rotacionado; o anterior vale até {}",
                tenantId,
                e.previousSecretUntil()));
    return rotacionado;
  }

  /**
   * Segredos com que a entrega deste tenant deve ser assinada.
   *
   * <p>Cai no segredo global só quando o tenant não tem registro ou é um registro anterior à
   * V005 — o mesmo desenho do destino global: conveniência de desenvolvimento, proibida em
   * produção pelo {@code GlobalTargetUrlReadinessGuard}, porque um segredo comum a todos permite a
   * um parceiro forjar o callback de outro.
   */
  @Transactional(readOnly = true)
  public SigningMaterial resolveSigningMaterial(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      return SigningMaterial.of(properties.secret());
    }
    return repository
        .findByTenantId(tenantId)
        .filter(e -> e.secret() != null)
        .map(e -> new SigningMaterial(e.secret(), e.usablePreviousSecret()))
        .orElseGet(
            () -> {
              log.warn(
                  "Tenant {} sem segredo próprio; assinando com o segredo global"
                      + " (aceitável só fora de produção)",
                  tenantId);
              return SigningMaterial.of(properties.secret());
            });
  }

  @Transactional
  public Optional<WebhookEndpoint> deactivate(String tenantId) {
    Optional<WebhookEndpoint> endpoint =
        repository.findByTenantId(tenantId).map(WebhookEndpoint::deactivate).map(repository::save);
    endpoint.ifPresent(e -> log.info("Endpoint de webhook do tenant {} desativado", tenantId));
    return endpoint;
  }

  @Transactional(readOnly = true)
  public List<WebhookEndpoint> list() {
    return repository.findAll();
  }

  @Transactional(readOnly = true)
  public Optional<WebhookEndpoint> find(String tenantId) {
    return repository.findByTenantId(tenantId);
  }
}
