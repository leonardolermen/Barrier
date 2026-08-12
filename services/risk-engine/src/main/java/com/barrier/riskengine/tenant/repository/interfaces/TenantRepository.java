package com.barrier.riskengine.tenant.repository.interfaces;

import com.barrier.riskengine.tenant.domain.Tenant;
import java.util.Optional;

/** Repositório de domínio dos tenants. */
public interface TenantRepository {

  Optional<Tenant> findById(String id);
}
