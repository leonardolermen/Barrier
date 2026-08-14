package com.barrier.riskengine.identity.service;

import com.barrier.commons.mask.Documents;
import com.barrier.riskengine.identity.client.BureauProvider;
import com.barrier.riskengine.identity.client.BureauQuery;
import com.barrier.riskengine.identity.client.BureauResult;
import com.barrier.riskengine.identity.client.BureauTrace;
import com.barrier.riskengine.identity.client.BureauUnavailableException;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.repository.interfaces.IdentityCheckRepository;
import com.barrier.riskengine.resilience.CircuitBreaker;
import com.barrier.riskengine.resilience.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
  private final CircuitBreakerRegistry breakers;
  private final boolean reuseEnabled;
  private final Duration reuseTtl;
  private final MeterRegistry registry;

  public IdentityService(
      List<BureauProvider> providers,
      IdentityCheckRepository repository,
      CircuitBreakerRegistry breakers,
      @Value("${barrier.identity.reuse.enabled:false}") boolean reuseEnabled,
      @Value("${barrier.identity.reuse.ttl:PT24H}") Duration reuseTtl,
      MeterRegistry registry) {
    this.providers = providers;
    this.repository = repository;
    this.breakers = breakers;
    this.reuseEnabled = reuseEnabled;
    this.reuseTtl = reuseTtl;
    this.registry = registry;
  }

  public IdentityResult verify(VerifyIdentityCommand command) {
    Optional<IdentityCheck> reusable = findReusable(command);
    if (reusable.isPresent()) {
      IdentityCheck original = reusable.get();
      IdentityCheck check = repository.save(IdentityCheck.reusing(command.assessmentId(), original));
      log.info(
          "Identidade de {} {} reaproveitada da consulta {} de {} (sem chamada ao bureau)",
          command.documentType(),
          Documents.mask(command.documentDigits()),
          original.id(),
          original.checkedAt());
      // Perfil não acompanha o reuso: CompanyProfile/PersonProfile são transientes e não ficam no
      // check. Para PF isso é aceitável — o SubjectProfile já foi enriquecido pela consulta
      // original (mesmo tenant) e o patch preserva campo ausente. Para PJ não seria: a
      // CorporateStructureRiskRule perderia o QSA e deixaria de disparar em silêncio — é por isso
      // que só CPF entra em findReusable.
      countCheck("reused");
      return new IdentityResult(check, null, null);
    }

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
      CircuitBreaker breaker = breakers.forName(provider.name());
      if (!breaker.allowRequest()) {
        // Sem esta recusa, cada avaliação pagaria o timeout inteiro de um provider que já se sabe
        // fora do ar — e a fila inteira ficaria lenta por causa de um terceiro.
        lastError = provider.name() + ": disjuntor aberto (provider em falha recente)";
        log.warn("Bureau {} com disjuntor aberto; pulando sem chamar", provider.name());
        continue;
      }
      try {
        BureauResult result = provider.check(query);
        breaker.recordSuccess();
        IdentityCheck check =
            save(command, toStatus(result.outcome()), provider.name(), result.detail(), result.trace());
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
        countCheck("fresh");
        return new IdentityResult(check, result.company(), result.person());
      } catch (BureauUnavailableException e) {
        // Só indisponibilidade conta para o disjuntor. Um erro de programação (NPE, parsing) não é
        // provider fora do ar, e abrir por causa dele esconderia o bug atrás de um UNAVAILABLE.
        breaker.recordFailure();
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
   * <p>Sem esse recorte, o {@code FakeCpfBureauProvider} ({@code @Order(100)}, último da cadeia)
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

  /**
   * Só CPF, só com a flag ligada. O recorte por tipo de documento não é cautela genérica: é a
   * consequência de o perfil do bureau (CompanyProfile/PersonProfile) não ser persistido no
   * check — reusar um check de PJ devolveria {@code company == null} e faria a
   * CorporateStructureRiskRule parar de disparar em silêncio.
   */
  private Optional<IdentityCheck> findReusable(VerifyIdentityCommand command) {
    if (!reuseEnabled || !"CPF".equals(command.documentType())) {
      return Optional.empty();
    }
    return repository.findReusable(
        command.tenantId(),
        command.documentType(),
        command.documentDigits(),
        command.name(),
        Instant.now().minus(reuseTtl));
  }

  /**
   * Conta de onde veio a verificação. Sem separar reuso de consulta fresca, uma queda de custo é
   * indistinguível de uma queda de tráfego — e uma flag de reuso ligada por engano numa base
   * grande não apareceria em lugar nenhum. {@code UNAVAILABLE} no fim da cadeia não conta em
   * nenhum dos dois: não houve verificação.
   */
  private void countCheck(String outcome) {
    registry.counter("barrier.identity.check", "outcome", outcome).increment();
  }

  private IdentityResult unavailable(VerifyIdentityCommand command, String provider, String detail) {
    return new IdentityResult(save(command, IdentityStatus.UNAVAILABLE, provider, detail), null, null);
  }

  private IdentityCheck save(
      VerifyIdentityCommand command, IdentityStatus status, String provider, String detail) {
    return save(command, status, provider, detail, null);
  }

  /** Grava a verificação com o rastro da consulta (id no provedor + payload redigido), se houver. */
  private IdentityCheck save(
      VerifyIdentityCommand command,
      IdentityStatus status,
      String provider,
      String detail,
      BureauTrace trace) {
    return repository.save(
        IdentityCheck.create(
            command.assessmentId(),
            command.tenantId(),
            command.documentType(),
            command.documentDigits(),
            command.name(),
            status,
            provider,
            detail,
            trace == null ? null : trace.providerReference(),
            trace == null ? null : trace.rawResponse()));
  }

  private static IdentityStatus toStatus(BureauResult.Outcome outcome) {
    return switch (outcome) {
      case MATCH -> IdentityStatus.VERIFIED;
      case NOT_FOUND -> IdentityStatus.NOT_FOUND;
      case MISMATCH -> IdentityStatus.MISMATCH;
      case DECEASED -> IdentityStatus.DECEASED;
    };
  }
}
