package com.barrier.riskengine.identity.service;

import com.barrier.commons.mask.Documents;
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

  public IdentityResult verify(VerifyIdentityCommand command) {
    List<BureauProvider> chain = chainFor(command.documentType());

    String maskedDoc = Documents.mask(command.documentDigits());
    if (chain.isEmpty()) {
      log.warn("Sem bureau para o tipo de documento {}", command.documentType());
      return unavailable(command, "nenhum", "Sem provider para " + command.documentType());
    }
    log.debug(
        "Verificando identidade {} {} na cadeia de bureaus {}",
        command.documentType(),
        maskedDoc,
        chain.stream().map(BureauProvider::name).toList());

    BureauQuery query =
        new BureauQuery(command.documentType(), command.documentDigits(), command.name());
    String lastError = null;

    for (BureauProvider provider : chain) {
      try {
        BureauResult result = provider.check(query);
        IdentityCheck check =
            save(command, toStatus(result.outcome()), provider.name(), result.detail());
        // O detail traz nome/razão social vindos do bureau (dado pessoal). Fica persistido em
        // identity_checks.detail, que é a evidência da decisão e tem controle de acesso — mas não
        // vai para o log, que é agregado e amplamente legível.
        log.info(
            "Bureau '{}' respondeu {} para {} {}{}",
            provider.name(),
            result.outcome(),
            command.documentType(),
            maskedDoc,
            result.company() != null ? " [perfil PJ obtido]" : "");
        log.debug("Detalhe do bureau '{}': {}", provider.name(), result.detail());
        return new IdentityResult(check, result.company());
      } catch (BureauUnavailableException e) {
        lastError = provider.name() + ": " + e.getMessage();
        log.warn("Bureau {} indisponível; tentando o próximo. {}", provider.name(), e.getMessage());
      }
    }

    // Toda a cadeia esgotada por indisponibilidade.
    return unavailable(command, "todos", lastError);
  }

  /**
   * Providers que atendem o tipo, em ordem de prioridade — <b>excluindo os não-autoritativos
   * quando existe pelo menos um autoritativo</b> para aquele tipo.
   *
   * <p>Sem esse recorte, o {@code StubBureauProvider} ({@code @Order(100)}, último da cadeia)
   * respondia MATCH sempre que o bureau real lançava {@link BureauUnavailableException}. O efeito
   * era converter indisponibilidade em identidade verificada — silenciosamente, e sem que o
   * {@code CpfBureauReadinessGuard} pudesse perceber, porque a configuração estava correta: o
   * bureau real <i>estava</i> habilitado, só não estava respondendo.
   *
   * <p>Com o recorte, o desfecho passa a ser {@link IdentityStatus#UNAVAILABLE}, que a
   * {@code IdentityRiskRule} converte em revisão humana.
   */
  private List<BureauProvider> chainFor(String documentType) {
    List<BureauProvider> supporting =
        providers.stream().filter(p -> p.supports(documentType)).toList();
    List<BureauProvider> authoritative =
        supporting.stream().filter(BureauProvider::authoritative).toList();
    if (authoritative.isEmpty() || authoritative.size() == supporting.size()) {
      return supporting;
    }
    log.debug(
        "Providers não-autoritativos removidos da cadeia de {}: {}",
        documentType,
        supporting.stream()
            .filter(p -> !p.authoritative())
            .map(BureauProvider::name)
            .toList());
    return authoritative;
  }

  private IdentityResult unavailable(VerifyIdentityCommand command, String provider, String detail) {
    return new IdentityResult(save(command, IdentityStatus.UNAVAILABLE, provider, detail), null);
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
