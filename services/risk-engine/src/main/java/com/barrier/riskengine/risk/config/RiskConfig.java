package com.barrier.riskengine.risk.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans de apoio às regras de risco. */
@Configuration
class RiskConfig {

  /** Relógio do sistema; injetável nas regras para permitir avaliação determinística em teste. */
  @Bean
  Clock clock() {
    return Clock.systemDefaultZone();
  }
}
