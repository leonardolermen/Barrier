package com.barrier.riskengine.tenant.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapeamento JPA de uma API key de tenant. Guarda o hash do segredo, nunca o segredo.
 *
 * <p>Sem {@code @ToString}: imprimiria o {@code secretHash} em log.
 */
@Entity
@Table(name = "tenant_api_keys")
@Getter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
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

}
