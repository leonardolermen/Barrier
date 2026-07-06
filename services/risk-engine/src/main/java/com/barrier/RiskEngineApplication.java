package com.barrier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ponto de entrada da Risk Engine API.
 *
 * <p>Fica no pacote raiz {@code com.barrier} para que o component scan, o {@code @EntityScan}
 * e os repositórios JPA cubram tanto {@code com.barrier.riskengine} quanto o módulo
 * compartilhado {@code com.barrier.commons} (outbox/eventos) sem configuração adicional.
 */
@SpringBootApplication
@EnableScheduling
public class RiskEngineApplication {

  public static void main(String[] args) {
    SpringApplication.run(RiskEngineApplication.class, args);
  }
}
