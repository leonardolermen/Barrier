package com.barrier.riskengine.tenant.repository.interfaces;

import com.barrier.riskengine.tenant.domain.ApiKey;
import java.util.Optional;

/** Acesso às credenciais de tenant. */
public interface ApiKeyRepository {

  ApiKey save(ApiKey apiKey);

  /** Busca pela parte pública da chave. */
  Optional<ApiKey> findByKeyId(String keyId);

  /** Quantidade de chaves emitidas e ainda válidas (usado pelo readiness guard). */
  long countActive();
}
