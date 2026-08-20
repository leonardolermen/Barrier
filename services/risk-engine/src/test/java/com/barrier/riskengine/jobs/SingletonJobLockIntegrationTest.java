package com.barrier.riskengine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.commons.jobs.SingletonJobLock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * O lease precisa ser exclusivo <b>entre réplicas concorrentes</b> e cobrir a janela inteira.
 *
 * <p>Precisa de banco de verdade: a exclusividade vem de o {@code INSERT ... ON CONFLICT DO UPDATE
 * ... WHERE} ser atômico no Postgres — pelo mesmo motivo que {@code ConcurrentClaimIntegrationTest}
 * existe para o {@code SKIP LOCKED}.
 *
 * <p>A primeira versão deste teste chamava {@code runIfLeader} cinco vezes <b>em sequência</b> e
 * exigia uma execução. Estava errado: terminada a execução o lease é liberado, e a chamada
 * seguinte deve mesmo rodar (é a próxima janela). O que o cenário real tem e a sequência não tinha
 * é <b>concorrência</b>.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.watchlist.refresh-cron=0 0 3 1 1 ?"
    })
@Testcontainers
class SingletonJobLockIntegrationTest {

  private static final Duration SEM_PISO = Duration.ZERO;
  private static final Duration TETO = Duration.ofMinutes(5);

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired SingletonJobLock lock;

  /** Cinco réplicas disparando o mesmo cron no mesmo instante: exatamente uma executa. */
  @Test
  void apenasUmaReplicaExecutaQuandoTodasDisparamJuntas() throws Exception {
    AtomicInteger execucoes = new AtomicInteger();
    CountDownLatch largada = new CountDownLatch(1);
    CountDownLatch fim = new CountDownLatch(5);

    try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
      for (int replica = 0; replica < 5; replica++) {
        pool.submit(
            () -> {
              try {
                largada.await();
                lock.runIfLeader(
                    "cron-concorrente",
                    TETO,
                    TETO,
                    () -> {
                      execucoes.incrementAndGet();
                      sleep(300); // segura o lease enquanto as outras tentam
                    });
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                fim.countDown();
              }
            });
      }
      largada.countDown();
      assertThat(fim.await(30, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(execucoes.get())
        .as("job singleton executou mais de uma vez — o lease não é exclusivo")
        .isEqualTo(1);
  }

  /**
   * O caso que motiva o piso ({@code lockAtLeastFor}). A réplica B tem o cron atrasado — clock
   * skew, scheduler ocupado, ou um pod que subiu depois — e dispara <b>depois</b> de A já ter
   * terminado. Sem o piso, B reivindica e reexecuta a mesma janela: 400 reavaliações pagas onde o
   * teto do job diz 200.
   */
  @Test
  void replicaAtrasadaNaoReexecutaAJanelaJaProcessada() {
    AtomicInteger execucoes = new AtomicInteger();

    lock.runIfLeader("cron-diario", TETO, TETO, execucoes::incrementAndGet);
    lock.runIfLeader("cron-diario", TETO, TETO, execucoes::incrementAndGet);

    assertThat(execucoes.get())
        .as("a janela foi reexecutada — o piso do lease não segurou")
        .isEqualTo(1);
  }

  /**
   * Sem piso, a próxima chamada roda: é o comportamento desejado para job frequente, onde
   * reexecutar cedo é barato e pular uma janela é pior.
   */
  @Test
  void semPisoOJobPodeRodarNaJanelaSeguinte() {
    AtomicInteger execucoes = new AtomicInteger();

    lock.runIfLeader("cron-frequente", SEM_PISO, TETO, execucoes::incrementAndGet);
    lock.runIfLeader("cron-frequente", SEM_PISO, TETO, execucoes::incrementAndGet);

    assertThat(execucoes.get()).isEqualTo(2);
  }

  @Test
  void jobsDistintosNaoSeBloqueiam() {
    AtomicInteger execucoes = new AtomicInteger();

    lock.runIfLeader("job-a", TETO, TETO, execucoes::incrementAndGet);
    lock.runIfLeader("job-b", TETO, TETO, execucoes::incrementAndGet);

    assertThat(execucoes.get()).isEqualTo(2);
  }

  /**
   * Job que estoura precisa soltar o lease até o piso — e não além. Sem isso, uma falha transitória
   * numa importação bloquearia a próxima janela inteira, e o resultado seria "a watchlist parou de
   * atualizar e ninguém percebeu": o modo de falha que esta parte do sistema existe para eliminar.
   */
  @Test
  void liberaOLeaseQuandoOJobFalha() {
    AtomicInteger execucoes = new AtomicInteger();

    assertThat(
            catching(
                () ->
                    lock.runIfLeader(
                        "job-que-falha",
                        SEM_PISO,
                        TETO,
                        () -> {
                          execucoes.incrementAndGet();
                          throw new IllegalStateException("falha simulada");
                        })))
        .as("a exceção do job precisa subir, não ser engolida pelo lock")
        .isInstanceOf(IllegalStateException.class);

    lock.runIfLeader("job-que-falha", SEM_PISO, TETO, execucoes::incrementAndGet);

    assertThat(execucoes.get())
        .as("lease não foi liberado após falha — a próxima janela ficaria bloqueada")
        .isEqualTo(2);
  }

  private static Throwable catching(Runnable action) {
    try {
      action.run();
      return null;
    } catch (RuntimeException e) {
      return e;
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
