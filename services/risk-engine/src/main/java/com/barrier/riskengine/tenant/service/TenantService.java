package com.barrier.riskengine.tenant.service;

import com.barrier.riskengine.tenant.domain.Tenant;
import com.barrier.riskengine.tenant.domain.UnknownTenantException;
import com.barrier.riskengine.tenant.repository.interfaces.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Busca um tenant por id e verifica se está ativo.
 *
 * <p><b>Isto não é autenticação.</b> Autenticar é papel do {@code ApiKeyService}, a partir da
 * credencial. Este serviço sobrou para os endpoints <b>administrativos</b>, que operam sobre um
 * tenant informado no path — legítimo ali porque quem chama já provou ser o operador do Barrier
 * ({@code AdminApiKeyFilter}). Nenhum endpoint de negócio deve usá-lo para descobrir "quem é o
 * chamador": era exatamente assim que o {@code X-Client-Id} autodeclarado dava acesso a dados de
 * outros clientes.
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
