package com.barrier.riskengine.tenant.service;

import com.barrier.riskengine.tenant.domain.ApiKey;
import com.barrier.riskengine.tenant.domain.ApiKeyMaterial;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import com.barrier.riskengine.tenant.domain.Tenant;
import com.barrier.riskengine.tenant.repository.interfaces.ApiKeyRepository;
import com.barrier.riskengine.tenant.repository.interfaces.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emissão e verificação de credenciais de tenant.
 *
 * <p>Toda falha de autenticação devolve {@link Optional#empty()} sem distinguir o motivo — chave
 * malformada, inexistente, revogada ou tenant inativo respondem igual. Distinguir daria um oráculo
 * para sondar quais {@code keyId} existem.
 */
@Service
public class ApiKeyService {

  private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

  private final ApiKeyRepository apiKeys;
  private final TenantRepository tenants;
  private final Clock clock;

  public ApiKeyService(ApiKeyRepository apiKeys, TenantRepository tenants, Clock clock) {
    this.apiKeys = apiKeys;
    this.tenants = tenants;
    this.clock = clock;
  }

  /** Autentica uma chave apresentada. Vazio = negar, sem detalhar o porquê ao chamador. */
  @Transactional(readOnly = true)
  public Optional<AuthenticatedTenant> authenticate(String presented) {
    return ApiKeyMaterial.parse(presented)
        .flatMap(parts -> apiKeys.findByKeyId(parts.keyId()).map(key -> new Attempt(parts, key)))
        .filter(attempt -> attempt.key().isActive())
        .filter(attempt -> ApiKeyMaterial.matches(attempt.parts().secret(), attempt.key().secretHash()))
        .flatMap(attempt -> activeTenant(attempt.key()));
  }

  /**
   * Emite uma chave nova. O valor em claro só existe no retorno — depois disto é irrecuperável.
   *
   * @return o material gerado, cujo {@code presentedValue()} deve ser entregue ao cliente
   */
  @Transactional
  public ApiKeyMaterial.Generated issue(String tenantId, String name) {
    Tenant tenant =
        tenants
            .findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant desconhecido: " + tenantId));
    ApiKeyMaterial.Generated generated = ApiKeyMaterial.generate();
    apiKeys.save(
        new ApiKey(
            UUID.randomUUID(),
            tenant.id(),
            generated.keyId(),
            generated.secretHash(),
            name,
            Instant.now(clock),
            null));
    log.info("API key '{}' emitida para o tenant {} (keyId {})", name, tenant.id(), generated.keyId());
    return generated;
  }

  private Optional<AuthenticatedTenant> activeTenant(ApiKey key) {
    return tenants
        .findById(key.tenantId())
        .filter(Tenant::active)
        .map(tenant -> new AuthenticatedTenant(tenant, key.name()));
  }

  private record Attempt(ApiKeyMaterial.Presented parts, ApiKey key) {}
}
