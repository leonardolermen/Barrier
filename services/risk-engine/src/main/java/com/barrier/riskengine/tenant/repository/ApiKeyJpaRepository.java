package com.barrier.riskengine.tenant.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ApiKeyJpaRepository extends JpaRepository<ApiKeyEntity, UUID> {

  Optional<ApiKeyEntity> findByKeyId(String keyId);
}
