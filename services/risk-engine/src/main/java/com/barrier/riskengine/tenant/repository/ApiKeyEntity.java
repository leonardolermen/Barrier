package com.barrier.riskengine.tenant.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento JPA de uma API key de tenant. Guarda o hash do segredo, nunca o segredo. */
@Entity
@Table(name = "tenant_api_keys")
public class ApiKeyEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "key_id", nullable = false, length = 40)
  private String keyId;

  @Column(name = "secret_hash", nullable = false, length = 64)
  private String secretHash;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected ApiKeyEntity() {
    // JPA
  }

  ApiKeyEntity(
      UUID id,
      String tenantId,
      String keyId,
      String secretHash,
      String name,
      Instant createdAt,
      Instant revokedAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.keyId = keyId;
    this.secretHash = secretHash;
    this.name = name;
    this.createdAt = createdAt;
    this.revokedAt = revokedAt;
  }

  UUID getId() {
    return id;
  }

  String getTenantId() {
    return tenantId;
  }

  String getKeyId() {
    return keyId;
  }

  String getSecretHash() {
    return secretHash;
  }

  String getName() {
    return name;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  Instant getRevokedAt() {
    return revokedAt;
  }
}
