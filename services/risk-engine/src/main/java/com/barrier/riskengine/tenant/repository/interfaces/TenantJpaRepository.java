package com.barrier.riskengine.tenant.repository.interfaces;

import com.barrier.riskengine.tenant.repository.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantJpaRepository extends JpaRepository<TenantEntity, String> {}
