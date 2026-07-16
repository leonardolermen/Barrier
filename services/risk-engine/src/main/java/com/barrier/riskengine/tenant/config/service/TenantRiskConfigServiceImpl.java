package com.barrier.riskengine.tenant.config.service;

import com.barrier.riskengine.tenant.config.repository.TenantRiskConfigRepository;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantRiskConfigServiceImpl implements TenantRiskConfigService {

  private final TenantRiskConfigRepository repository;

  public TenantRiskConfigServiceImpl(TenantRiskConfigRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public int getInt(String tenantId, String ruleCode, String paramKey, int defaultValue) {
    return repository
        .find(tenantId, ruleCode, paramKey)
        .map(entry -> Integer.parseInt(entry.paramValue()))
        .orElse(defaultValue);
  }

  @Override
  @Transactional(readOnly = true)
  public Set<String> getStringSet(
      String tenantId, String ruleCode, String paramKey, Set<String> defaultValue) {
    return repository
        .find(tenantId, ruleCode, paramKey)
        .map(
            entry -> {
              Set<String> merged = new LinkedHashSet<>(defaultValue);
              merged.addAll(Arrays.asList(entry.paramValue().split(",")));
              return merged;
            })
        .orElse(defaultValue);
  }
}
