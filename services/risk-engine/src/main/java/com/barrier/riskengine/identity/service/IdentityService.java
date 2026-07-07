package com.barrier.riskengine.identity.service;

import com.barrier.riskengine.identity.client.BureauProvider;
import com.barrier.riskengine.identity.client.BureauQuery;
import com.barrier.riskengine.identity.client.BureauResult;
import com.barrier.riskengine.identity.client.BureauUnavailableException;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.repository.IdentityCheckRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Verifica a identidade de um documento consultando bureaus (Gateway atrás de interface).
 *
 * <p>Seleciona os providers que atendem o tipo de documento (Strategy), em ordem de
 * prioridade (a lista chega ordenada por {@code @Order}). Percorre a cadeia com <b>fallback</b>:
 * um bureau indisponível ({@link BureauUnavailableException}) faz cair para o próximo. Um
 * resultado definitivo (MATCH/NOT_FOUND/MISMATCH) encerra a cadeia. Se todos os providers
 * estiverem indisponíveis, o resultado é {@link IdentityStatus#UNAVAILABLE} — e a avaliação
 * segue mesmo assim.
 */
@Service
public class IdentityService {

  private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

  private final List<BureauProvider> providers;
  private final IdentityCheckRepository repository;

  public IdentityService(List<BureauProvider> providers, IdentityCheckRepository repository) {
    this.providers = providers;
    this.repository = repository;
  }

  public IdentityCheck verify(VerifyIdentityCommand command) {
    List<BureauProvider> chain =
        providers.stream().filter(p -> p.supports(command.documentType())).toList();

    if (chain.isEmpty()) {
      log.warn("Sem bureau para o tipo de documento {}", command.documentType());
      return save(command, IdentityStatus.UNAVAILABLE, "nenhum", "Sem provider para " + command.documentType());
    }

    BureauQuery query =
        new BureauQuery(command.documentType(), command.documentDigits(), command.name());
    String lastError = null;

    for (BureauProvider provider : chain) {
      try {
        BureauResult result = provider.check(query);
        return save(command, toStatus(result.outcome()), provider.name(), result.detail());
      } catch (BureauUnavailableException e) {
        lastError = provider.name() + ": " + e.getMessage();
        log.warn("Bureau {} indisponível; tentando o próximo. {}", provider.name(), e.getMessage());
      }
    }

    // Toda a cadeia esgotada por indisponibilidade.
    return save(command, IdentityStatus.UNAVAILABLE, "todos", lastError);
  }

  private IdentityCheck save(
      VerifyIdentityCommand command, IdentityStatus status, String provider, String detail) {
    return repository.save(IdentityCheck.create(command.assessmentId(), status, provider, detail));
  }

  private static IdentityStatus toStatus(BureauResult.Outcome outcome) {
    return switch (outcome) {
      case MATCH -> IdentityStatus.VERIFIED;
      case NOT_FOUND -> IdentityStatus.NOT_FOUND;
      case MISMATCH -> IdentityStatus.MISMATCH;
    };
  }
}
