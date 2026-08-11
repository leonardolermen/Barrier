package com.barrier.webhook.repository;

import com.barrier.webhook.domain.WebhookEndpoint;
import java.util.List;
import java.util.Optional;

/** Endpoints de callback registrados, um por tenant. */
public interface WebhookEndpointRepository {

  WebhookEndpoint save(WebhookEndpoint endpoint);

  Optional<WebhookEndpoint> findByTenantId(String tenantId);

  List<WebhookEndpoint> findAll();
}
