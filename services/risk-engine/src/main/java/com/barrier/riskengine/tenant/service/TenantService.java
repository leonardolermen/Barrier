package com.barrier.riskengine.tenant.service;

import com.barrier.riskengine.tenant.domain.Tenant;
import com.barrier.riskengine.tenant.domain.UnknownTenantException;
import com.barrier.riskengine.tenant.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolve o tenant a partir do {@code X-Client-Id}. Pré-autenticação, confia no header; quando
 * a auth por API key chegar, o tenant passará a ser derivado da key (o header é ignorado).
 */
@Service
public class TenantService {

  private final TenantRepository repository;

  public TenantService(TenantRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public Tenant resolve(String clientId) {
    if (clientId == null || clientId.isBlank()) {
      throw new UnknownTenantException("Header X-Client-Id é obrigatório");
    }
    Tenant tenant =
        repository
            .findById(clientId)
            .orElseThrow(() -> new UnknownTenantException("Cliente desconhecido: " + clientId));
    if (!tenant.active()) {
      throw new UnknownTenantException("Cliente inativo: " + clientId);
    }
    return tenant;
  }
}
