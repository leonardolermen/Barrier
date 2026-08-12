package com.barrier.riskengine.tenant.repository;

import com.barrier.riskengine.tenant.domain.Tenant;
import java.util.Optional;

import com.barrier.riskengine.tenant.repository.interfaces.TenantJpaRepository;
import com.barrier.riskengine.tenant.repository.interfaces.TenantRepository;
import org.springframework.stereotype.Repository;

@Repository
class TenantRepositoryImpl implements TenantRepository {

  private final TenantJpaRepository jpa;

  TenantRepositoryImpl(TenantJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<Tenant> findById(String id) {
    return jpa.findById(id).map(e -> new Tenant(e.getId(), e.getName(), e.isActive()));
  }
}
