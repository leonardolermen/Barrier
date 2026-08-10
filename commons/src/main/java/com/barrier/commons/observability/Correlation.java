package com.barrier.commons.observability;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * Id de correlação de uma requisição, propagado pelo MDC.
 *
 * <p>Vive no {@code commons} porque a correlação atravessa tudo: nasce no filtro HTTP do
 * risk-engine, precisa ser <b>restaurada</b> no processamento assíncrono (que roda noutra thread,
 * onde o MDC do servlet não existe) e reaparece no consumidor de Kafka do webhook-api. Deixá-la no
 * pacote {@code web} obrigaria o serviço a depender da camada de entrada para algo que não é
 * entrada.
 *
 * <p>Sem isto, o log da decisão não tinha nem {@code correlationId} nem {@code assessmentId} — a
 * decisão acontece num {@code @Scheduled}, e o MDC só existia na thread do servlet. Investigar uma
 * aprovação indevida era {@code grep} em log de texto por documento mascarado.
 */
public final class Correlation {

  public static final String MDC_KEY = "correlationId";

  private Correlation() {}

  /** Id da requisição atual; {@code null} fora de um contexto correlacionado. */
  public static String current() {
    return MDC.get(MDC_KEY);
  }

  /** Id da requisição atual, ou um novo quando não houver — para quem precisa sempre de um. */
  public static String currentOrNew() {
    String current = current();
    return current == null || current.isBlank() ? UUID.randomUUID().toString() : current;
  }

  /**
   * Executa com o id de correlação no MDC e restaura o estado anterior ao final.
   *
   * <p>Restaurar (em vez de limpar) importa porque o processamento assíncrono roda numa thread de
   * pool reaproveitada: deixar o id de uma avaliação pendurado contaminaria a próxima.
   */
  public static void run(String correlationId, Runnable action) {
    String previous = MDC.get(MDC_KEY);
    if (correlationId != null && !correlationId.isBlank()) {
      MDC.put(MDC_KEY, correlationId);
    }
    try {
      action.run();
    } finally {
      if (previous == null) {
        MDC.remove(MDC_KEY);
      } else {
        MDC.put(MDC_KEY, previous);
      }
    }
  }
}
