package com.barrier.riskengine.tenant.repository.interfaces;

import java.util.Optional;
import java.util.UUID;

import com.barrier.riskengine.tenant.repository.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyJpaRepository extends JpaRepository<ApiKeyEntity, UUID> {

  Optional<ApiKeyEntity> findByKeyId(String keyId);
}
