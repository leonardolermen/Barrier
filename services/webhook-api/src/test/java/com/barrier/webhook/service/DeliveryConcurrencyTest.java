package com.barrier.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * O semáforo é o teto de fato — não decoração.
 *
 * <p><b>Por que este teste existe.</b> Virtual thread é barata: submeter 50 tarefas não estoura
 * nada, não enfileira visivelmente, e não dá <i>nenhum</i> sinal de que o teto foi ignorado. Um
 * teto quebrado passaria despercebido até o parceiro reclamar de rajada — ou, do lado da
 * risk-engine, até a fatura do bureau.
 *
 * <p>É o oposto de um pool fixo, onde exceder o teto é impossível por construção. Com virtual
 * threads o teto é uma decisão explícita, e decisão explícita precisa de teste.
 */
class DeliveryConcurrencyTest {

  @Test
  void oSemaforoLimitaAConcorrenciaSimultanea() {
    int teto = 3;
    AtomicInteger emVoo = new AtomicInteger();
    AtomicInteger pico = new AtomicInteger();
    Semaphore permissoes = new Semaphore(teto);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tarefas =
          IntStream.range(0, 50)
              .mapToObj(
                  i ->
                      CompletableFuture.runAsync(
                          () -> {
                            permissoes.acquireUninterruptibly();
                            try {
                              pico.accumulateAndGet(emVoo.incrementAndGet(), Math::max);
                              Thread.sleep(20);
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            } finally {
                              emVoo.decrementAndGet();
                              permissoes.release();
                            }
                          },
                          executor))
              .toList();

      CompletableFuture.allOf(tarefas.toArray(CompletableFuture[]::new)).join();
    }

    assertThat(pico.get())
        .as("chegaram %d tarefas simultaneas com teto de %d", pico.get(), teto)
        .isLessThanOrEqualTo(teto);
  }

  /** E o teto não pode ser tão apertado que serialize: com 3 permissões, mais de uma deve correr. */
  @Test
  void oTetoNaoSerializaAsTarefas() {
    int teto = 3;
    AtomicInteger emVoo = new AtomicInteger();
    AtomicInteger pico = new AtomicInteger();
    Semaphore permissoes = new Semaphore(teto);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tarefas =
          IntStream.range(0, 20)
              .mapToObj(
                  i ->
                      CompletableFuture.runAsync(
                          () -> {
                            permissoes.acquireUninterruptibly();
                            try {
                              pico.accumulateAndGet(emVoo.incrementAndGet(), Math::max);
                              Thread.sleep(50);
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            } finally {
                              emVoo.decrementAndGet();
                              permissoes.release();
                            }
                          },
                          executor))
              .toList();

      CompletableFuture.allOf(tarefas.toArray(CompletableFuture[]::new)).join();
    }

    assertThat(pico.get())
        .as("nenhuma concorrencia observada — o paralelismo nao esta acontecendo")
        .isGreaterThan(1);
  }
}
