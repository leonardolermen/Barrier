package com.barrier.riskengine.risk.registry.service;

import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import com.barrier.riskengine.risk.registry.repository.RiskRuleRegistryRepository;
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

  @Override
  @Transactional(readOnly = true)
  public boolean isActive(String ruleCode) {
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

  @Override
  @Transactional
  public RiskRuleRegistryEntry upsert(
      String ruleCode,
      String description,
      String criticality,
      boolean enabled,
      Instant validFrom,
      Instant validUntil) {
    return repository.upsert(ruleCode, description, criticality, enabled, validFrom, validUntil);
  }
}
