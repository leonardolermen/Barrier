package com.barrier.riskengine.risk.registry.service;

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
      Instant validUntil) {
    if (RegulatoryRiskRules.isRegulatory(ruleCode)
        && (!enabled || validFrom != null || validUntil != null)) {
      throw new IllegalArgumentException(
          "Regra regulatória '"
              + ruleCode
              + "' não pode ser desabilitada nem ter vigência limitada. Protegidas: "
              + RegulatoryRiskRules.codes());
    }
    return repository.upsert(ruleCode, description, criticality, enabled, validFrom, validUntil);
  }
}
