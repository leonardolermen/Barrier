package com.barrier.webhook.repository;

import com.barrier.webhook.domain.WebhookEndpoint;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class WebhookEndpointRepositoryImpl implements WebhookEndpointRepository {

  private final WebhookEndpointJpaRepository jpa;

  WebhookEndpointRepositoryImpl(WebhookEndpointJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public WebhookEndpoint save(WebhookEndpoint endpoint) {
    WebhookEndpointEntity entity =
        jpa.findById(endpoint.tenantId()).orElseGet(WebhookEndpointEntity::new);
    entity.setTenantId(endpoint.tenantId());
    entity.setTargetUrl(endpoint.targetUrl());
    entity.setSecret(endpoint.secret());
    entity.setPreviousSecret(endpoint.previousSecret());
    entity.setPreviousSecretUntil(endpoint.previousSecretUntil());
    entity.setActive(endpoint.active());
    entity.setCreatedAt(entity.getCreatedAt() == null ? endpoint.createdAt() : entity.getCreatedAt());
    entity.setUpdatedAt(endpoint.updatedAt());
    return toDomain(jpa.save(entity));
  }

  @Override
  public Optional<WebhookEndpoint> findByTenantId(String tenantId) {
    return jpa.findById(tenantId).map(WebhookEndpointRepositoryImpl::toDomain);
  }

  @Override
  public List<WebhookEndpoint> findAll() {
    return jpa.findAll().stream().map(WebhookEndpointRepositoryImpl::toDomain).toList();
  }

  private static WebhookEndpoint toDomain(WebhookEndpointEntity entity) {
    return new WebhookEndpoint(
        entity.getTenantId(),
        entity.getTargetUrl(),
        entity.getSecret(),
        entity.getPreviousSecret(),
        entity.getPreviousSecretUntil(),
        entity.isActive(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
