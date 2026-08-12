package com.barrier.webhook.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mapeamento JPA do endpoint de callback de um tenant.
 *
 * <p>Sem {@code @ToString}: imprimiria o segredo de HMAC em log.
 */
@Entity
@Table(name = "webhook_endpoints")
@Getter(AccessLevel.PACKAGE)
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

}
