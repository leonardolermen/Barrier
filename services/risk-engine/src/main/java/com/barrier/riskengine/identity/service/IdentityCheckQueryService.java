package com.barrier.riskengine.identity.service;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.repository.interfaces.IdentityCheckRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Leitura de uma verificação de identidade pelo id — o portão do módulo {@code identity} para quem
 * precisa recuperar a evidência <b>exata</b> que sustentou uma decisão.
 *
 * <p>É a metade de {@code risk_scores.identity_check_id} que faltava: a V028 gravou o ponteiro
 * justamente porque uma avaliação retentada deixa várias linhas em {@code identity_checks} com o
 * mesmo {@code assessment_id}, e nada dizia qual valeu. Sem esta leitura, o ponteiro apontava para
 * um dado que nenhum caminho de produção buscava.
 */
@Service
public class IdentityCheckQueryService {

  private final IdentityCheckRepository repository;

  public IdentityCheckQueryService(IdentityCheckRepository repository) {
    this.repository = repository;
  }

  public Optional<IdentityCheck> findById(UUID id) {
    return id == null ? Optional.empty() : repository.findById(id);
  }
}
