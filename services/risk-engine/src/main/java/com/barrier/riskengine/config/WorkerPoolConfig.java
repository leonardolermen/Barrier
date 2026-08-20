package com.barrier.riskengine.config;

import com.barrier.commons.concurrency.WorkerPoolReadinessGuard;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Torna executável a amarra que estava só em comentário no {@code application.yml}: o teto de
 * avaliações simultâneas tem de caber no pool de conexões.
 *
 * <p>A reserva é 2 — a ingestão HTTP e os demais {@code @Scheduled} (outbox, importador de
 * watchlist, poller de assurance) também tiram conexão do mesmo pool e não passam pelo semáforo do
 * {@code AssessmentProcessor}.
 */
@Configuration
public class WorkerPoolConfig {

  private static final int RESERVA = 2;

  @Bean
  public WorkerPoolReadinessGuard assessmentWorkerPoolReadinessGuard(
      DataSource dataSource, @Value("${barrier.assessment.workers:4}") int workers) {
    return new WorkerPoolReadinessGuard(dataSource, "barrier.assessment.workers", workers, RESERVA);
  }
}
