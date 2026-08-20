package com.barrier.webhook.config;

import com.barrier.commons.jobs.SingletonJobLock;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Declara o {@link SingletonJobLock} explicitamente, em vez de escaneá-lo.
 *
 * <p>A {@code WebhookApplication} escaneia <b>apenas</b> {@code com.barrier.webhook}, de propósito:
 * o {@code commons} carrega os beans de outbox, que são da Risk Engine e não têm o que fazer aqui.
 * Ampliar o scan para {@code com.barrier} traria todos eles junto.
 *
 * <p>Por isso a dependência é <b>escolhida uma a uma</b>: este serviço pega do {@code commons} o
 * contrato de evento e agora o lease de job, e nada mais. Um {@code @Bean} explícito também deixa
 * a escolha legível — quem abrir este arquivo vê exatamente o que foi importado da biblioteca
 * compartilhada, sem precisar deduzir de um padrão de scan.
 *
 * <p>O lease grava em {@code job_locks} do schema {@code webhook} (V006), não no da Risk Engine:
 * cada deployable é dono do seu schema, e o escopo do lock é por serviço — as réplicas da Webhook
 * API coordenam entre si.
 */
@Configuration
public class JobLockConfig {

  /**
   * O relógio vem fixo em {@link Clock#systemUTC()} e não de um bean: a Webhook API não tem um
   * bean de {@code Clock} (a Risk Engine tem), e declarar um só para isto criaria um bean global
   * que nada mais consome — e que colidiria com o dia em que este serviço precisar do próprio.
   */
  @Bean
  public SingletonJobLock singletonJobLock(JdbcTemplate jdbcTemplate) {
    return new SingletonJobLock(jdbcTemplate, Clock.systemUTC());
  }
}
