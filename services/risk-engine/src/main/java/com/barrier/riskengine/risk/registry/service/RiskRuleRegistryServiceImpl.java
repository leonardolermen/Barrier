package com.barrier.riskengine.risk.registry.service;

import com.barrier.riskengine.policy.PolicyProvenance;
import com.barrier.riskengine.policy.RegistryPolicyState;
import java.time.Instant;
import java.util.Optional;
import com.barrier.riskengine.risk.registry.domain.RegulatoryRiskRules;
import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import com.barrier.riskengine.risk.registry.repository.interfaces.RiskRuleRegistryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskRuleRegistryServiceImpl implements RiskRuleRegistryService {

  private final RiskRuleRegistryRepository repository;
  private final Clock clock;

  public RiskRuleRegistryServiceImpl(RiskRuleRegistryRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /**
   * Regra regulatória é sempre ativa, independentemente do que estiver gravado no registry —
   * segunda camada de defesa, para o caso de uma linha ter sido escrita direto no banco ou por
   * uma versão anterior desta API, quando desligá-las ainda era possível.
   */
  @Override
  @Transactional(readOnly = true)
  public boolean isActive(String ruleCode) {
    if (RegulatoryRiskRules.isRegulatory(ruleCode)) {
      return true;
    }
    return repository
        .findByRuleCode(ruleCode)
        .map(entry -> entry.activeAt(Instant.now(clock)))
        .orElse(true);
  }

  @Override
  @Transactional(readOnly = true)
  public List<RiskRuleRegistryEntry> findAll() {
    return repository.findAll();
  }

  /**
   * Uma regra regulatória pode ter descrição/criticidade ajustadas, mas nunca ser desligada nem
   * ganhar janela de vigência — as duas coisas equivalem a desligar o controle.
   */
  @Override
  @Transactional
  public RiskRuleRegistryEntry upsert(
      String ruleCode,
      String description,
      String criticality,
      boolean enabled,
      Instant validFrom,
      Instant validUntil,
      String updatedBy) {
    if (RegulatoryRiskRules.isRegulatory(ruleCode)
        && (!enabled || validFrom != null || validUntil != null)) {
      throw new IllegalArgumentException(
          "Regra regulatória '"
              + ruleCode
              + "' não pode ser desabilitada nem ter vigência limitada. Protegidas: "
              + RegulatoryRiskRules.codes());
    }
    if (updatedBy == null || updatedBy.isBlank()) {
      // Mesma exigência do override por tenant. Ligar e desligar regra de risco sem autoria é
      // trilha que não responde a pergunta que o regulador faz: quem autorizou.
      throw new IllegalArgumentException("updatedBy obrigatório");
    }
    return repository.upsert(
        ruleCode, description, criticality, enabled, validFrom, validUntil, updatedBy);
  }

  /**
   * Três casos, e a diferença entre eles é o produto deste método:
   *
   * <ol>
   *   <li>há entrada de histórico em vigor no instante → é ela, com autoria ({@code FROM_HISTORY});
   *   <li>não há histórico nenhum, mas há linha no registry → a regra nunca foi alterada, e o que
   *       se lê hoje vigia desde a semente ({@code UNCHANGED_SINCE_SEED});
   *   <li>não há histórico <b>nem linha</b> → a regra nunca foi registrada. Não é lacuna: o registry
   *       é kill switch, não allowlist, e regra sem linha é ativa (fail-open, ver {@link #isActive}).
   *       A V016 semeia seis famílias e o motor tem dezesseis, então este é o caso <b>comum</b> —
   *       tratá-lo como desconhecido marcaria a maioria das regras como sem autoria apurável
   *       ({@code NOT_REGISTERED});
   *   <li>há histórico, todo posterior ao instante → o estado de então <b>não existe em lugar
   *       nenhum</b>, porque a V033 grava o estado novo de cada mudança e não o anterior. Vazio.
   * </ol>
   *
   * <p>Confundir 2 com 3 seria afirmar o estado de hoje como se fosse o de então, que é exatamente
   * o erro que motivou a V033 a existir.
   */
  @Override
  public Optional<RegistryPolicyState> stateAsOf(String ruleCode, Instant at) {
    Optional<RegistryPolicyState> gravado = repository.historyAsOf(ruleCode, at);
    if (gravado.isPresent()) {
      return gravado;
    }
    if (repository.hasAnyHistory(ruleCode)) {
      return Optional.empty();
    }
    return Optional.of(
        repository
            .findByRuleCode(ruleCode)
            .map(
                e ->
                    new RegistryPolicyState(
                        e.ruleCode(),
                        e.enabled(),
                        e.criticality().name(),
                        e.description(),
                        e.validFrom(),
                        e.validUntil(),
                        null,
                        null,
                        PolicyProvenance.UNCHANGED_SINCE_SEED))
            .orElseGet(
                () ->
                    new RegistryPolicyState(
                        ruleCode,
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        PolicyProvenance.NOT_REGISTERED)));
  }

  @Override
  public java.util.List<RegistryPolicyState> history(String ruleCode) {
    return repository.history(ruleCode);
  }
}
