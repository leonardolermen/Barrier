package com.barrier.webhook.config;

import com.barrier.commons.concurrency.WorkerPoolReadinessGuard;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Torna executável a amarra que estava só em comentário no {@code application.yml}: o teto de
 * entregas simultâneas tem de caber no pool de conexões.
 *
 * <p>Bean explícito e não {@code @Component} pelo mesmo motivo do {@link JobLockConfig}: a {@code
 * WebhookApplication} escaneia apenas {@code com.barrier.webhook}, então nada do {@code commons}
 * entra por scan — o que vem de lá é escolhido um a um, e fica legível que foi.
 *
 * <p>A reserva é 2: o listener do Kafka grava a entrega e o {@code DeliveryReconciliationJob}
 * consulta, ambos fora do semáforo do {@code WebhookDeliveryService}.
 */
@Configuration
public class WorkerPoolConfig {

  private static final int RESERVA = 2;

  @Bean
  public WorkerPoolReadinessGuard webhookWorkerPoolReadinessGuard(
      DataSource dataSource, @Value("${barrier.webhook.workers:3}") int workers) {
    return new WorkerPoolReadinessGuard(dataSource, "barrier.webhook.workers", workers, RESERVA);
  }
}
