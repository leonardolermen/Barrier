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
 * Verifica a identidade de um documento consultando um bureau (Gateway atrás de interface).
 *
 * <p>Seleciona o provider por tipo de documento (Strategy). A indisponibilidade do bureau é
 * registrada como {@link IdentityStatus#UNAVAILABLE} e não interrompe a avaliação.
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
    BureauProvider provider =
        providers.stream().filter(p -> p.supports(command.documentType())).findFirst().orElse(null);

    if (provider == null) {
      log.warn("Sem bureau para o tipo de documento {}", command.documentType());
      return repository.save(
          IdentityCheck.create(
              command.assessmentId(),
              IdentityStatus.UNAVAILABLE,
              "nenhum",
              "Sem provider para " + command.documentType()));
    }

    IdentityCheck check;
    try {
      BureauResult result =
          provider.check(
              new BureauQuery(
                  command.documentType(), command.documentDigits(), command.name()));
      check =
          IdentityCheck.create(
              command.assessmentId(), toStatus(result.outcome()), provider.name(), result.detail());
    } catch (BureauUnavailableException e) {
      log.warn("Bureau {} indisponível: {}", provider.name(), e.getMessage());
      check =
          IdentityCheck.create(
              command.assessmentId(), IdentityStatus.UNAVAILABLE, provider.name(), e.getMessage());
    }
    return repository.save(check);
  }

  private static IdentityStatus toStatus(BureauResult.Outcome outcome) {
    return switch (outcome) {
      case MATCH -> IdentityStatus.VERIFIED;
      case NOT_FOUND -> IdentityStatus.NOT_FOUND;
      case MISMATCH -> IdentityStatus.MISMATCH;
    };
  }
}
