package com.barrier.webhook.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Mapeamento JPA do endpoint de callback de um tenant. */
@Entity
@Table(name = "webhook_endpoints")
class WebhookEndpointEntity {

  @Id
  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "target_url", nullable = false, length = 500)
  private String targetUrl;

  /**
   * Em texto: assinar exige o valor. Diferente das API keys dos tenants, que ficam como hash —
   * lá basta comparar. Criptografia em repouso é item da Fase 6 e vale para esta coluna.
   */
  @Column(name = "secret", length = 120)
  private String secret;

  @Column(name = "previous_secret", length = 120)
  private String previousSecret;

  @Column(name = "previous_secret_until")
  private Instant previousSecretUntil;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WebhookEndpointEntity() {
    // JPA
  }

  String getTenantId() {
    return tenantId;
  }

  void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  String getTargetUrl() {
    return targetUrl;
  }

  void setTargetUrl(String targetUrl) {
    this.targetUrl = targetUrl;
  }

  String getSecret() {
    return secret;
  }

  void setSecret(String secret) {
    this.secret = secret;
  }

  String getPreviousSecret() {
    return previousSecret;
  }

  void setPreviousSecret(String previousSecret) {
    this.previousSecret = previousSecret;
  }

  Instant getPreviousSecretUntil() {
    return previousSecretUntil;
  }

  void setPreviousSecretUntil(Instant previousSecretUntil) {
    this.previousSecretUntil = previousSecretUntil;
  }

  boolean isActive() {
    return active;
  }

  void setActive(boolean active) {
    this.active = active;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  Instant getUpdatedAt() {
    return updatedAt;
  }

  void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
