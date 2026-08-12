package com.barrier.riskengine.risk.registry.repository.interfaces;

import com.barrier.riskengine.risk.registry.repository.RiskRuleRegistryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskRuleRegistryJpaRepository extends JpaRepository<RiskRuleRegistryEntity, String> {}
