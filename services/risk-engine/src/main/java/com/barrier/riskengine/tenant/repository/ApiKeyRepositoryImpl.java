package com.barrier.riskengine.tenant.repository;

import com.barrier.riskengine.tenant.domain.ApiKey;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class ApiKeyRepositoryImpl implements ApiKeyRepository {

  private final ApiKeyJpaRepository jpa;

  ApiKeyRepositoryImpl(ApiKeyJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public ApiKey save(ApiKey apiKey) {
    return toDomain(
        jpa.save(
            new ApiKeyEntity(
                apiKey.id(),
                apiKey.tenantId(),
                apiKey.keyId(),
                apiKey.secretHash(),
                apiKey.name(),
                apiKey.createdAt(),
                apiKey.revokedAt())));
  }

  @Override
  public Optional<ApiKey> findByKeyId(String keyId) {
    return jpa.findByKeyId(keyId).map(ApiKeyRepositoryImpl::toDomain);
  }

  @Override
  public long countActive() {
    return jpa.findAll().stream().filter(e -> e.getRevokedAt() == null).count();
  }

  private static ApiKey toDomain(ApiKeyEntity e) {
    return new ApiKey(
        e.getId(),
        e.getTenantId(),
        e.getKeyId(),
        e.getSecretHash(),
        e.getName(),
        e.getCreatedAt(),
        e.getRevokedAt());
  }
}
