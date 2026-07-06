package com.barrier.riskengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ponto de entrada da Risk Engine API.
 *
 * <p>Escaneia também o módulo {@code commons} (outbox/eventos), que fornece entidades JPA,
 * repositórios e componentes reutilizados por esta aplicação.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"com.barrier.riskengine", "com.barrier.commons"})
@EntityScan(basePackages = {"com.barrier.riskengine", "com.barrier.commons"})
@EnableJpaRepositories(basePackages = {"com.barrier.riskengine", "com.barrier.commons"})
public class RiskEngineApplication {

  public static void main(String[] args) {
    SpringApplication.run(RiskEngineApplication.class, args);
  }
}
