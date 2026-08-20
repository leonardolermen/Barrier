package com.barrier.riskengine.screening;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.commons.name.NameTokens;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.repository.interfaces.WatchlistEntryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Mede o ganho do blocking sobre uma base do tamanho de produção.
 *
 * <p><b>Por que medir e não confiar no raciocínio.</b> "Índice é mais rápido que varredura" é
 * verdade em geral e não diz nada sobre este caso: o ganho depende de quantos candidatos o
 * trigrama devolve, e um blocking frouxo demais devolveria a base inteira com o custo extra de
 * consultar o índice — seria mais lento que o {@code findAll()} que substituiu. A única forma de
 * saber é medir com volume.
 *
 * <p>A base sintética tem a ordem de grandeza da real (OFAC SDN+ALT com apelidos, CSNU, CEIS/CNEP e
 * PEP da CGU somam ~10^5). O conteúdo é gerado, mas a <b>distribuição</b> importa e é imitada:
 * sobrenomes brasileiros muito repetidos, que é o que faz o blocking sofrer — se todo mundo se
 * chama SILVA, procurar por SILVA traz meia base.
 *
 * <p>Não é benchmark de precisão (sem JMH, sem warmup rigoroso, uma execução por caminho). É uma
 * medida de <b>ordem de grandeza</b>, que é a pergunta em aberto: 2× não justificaria a migration,
 * 100× justifica.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.watchlist.refresh-cron=0 0 3 1 1 ?"
    })
@Testcontainers
@Tag("benchmark") // fora da build padrão; ver maven-surefire-plugin no pom raiz
class BlockingBenchmarkIntegrationTest {

  /**
   * Ordem de grandeza da base real. Não é meio milhão porque o tempo de carga dominaria a suíte —
   * e o que se quer medir é a diferença entre os dois caminhos de LEITURA, que aparece igual aqui.
   */
  private static final int ENTRADAS = 100_000;

  private static final double BLOCKING = 0.45;

  /** Sobrenomes que se repetem muito: é o caso difícil para blocking, não o fácil. */
  private static final String[] SOBRENOMES = {
    "SILVA", "SANTOS", "OLIVEIRA", "SOUZA", "RODRIGUES", "FERREIRA", "ALVES", "PEREIRA",
    "LIMA", "GOMES", "COSTA", "RIBEIRO", "MARTINS", "CARVALHO", "ALMEIDA", "LOPES"
  };

  private static final String[] PRENOMES = {
    "JOSE", "MARIA", "ANTONIO", "JOAO", "ANA", "CARLOS", "PAULO", "PEDRO",
    "LUCAS", "MARCOS", "LUIZ", "GABRIEL", "RAFAEL", "DANIEL", "MARCELO", "BRUNO"
  };

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired WatchlistEntryRepository repository;
  @Autowired JdbcTemplate jdbc;

  private static boolean carregada;

  @BeforeAll
  static void avisa() {
    System.out.println("\n=== benchmark de blocking: carregando base sintética ===");
  }

  private void carregaUmaVez() {
    if (carregada) {
      return;
    }
    Random random = new Random(42); // semente fixa: a medida precisa ser comparável entre execuções
    List<WatchlistRecord> lote = new ArrayList<>(ENTRADAS);
    for (int i = 0; i < ENTRADAS; i++) {
      String nome =
          PRENOMES[random.nextInt(PRENOMES.length)]
              + " "
              + PRENOMES[random.nextInt(PRENOMES.length)]
              + " "
              + SOBRENOMES[random.nextInt(SOBRENOMES.length)]
              + " "
              + SOBRENOMES[random.nextInt(SOBRENOMES.length)];
      lote.add(new WatchlistRecord("BENCH", MatchType.SANCTION, null, null, nome, "sintetico"));
    }
    long inicio = System.currentTimeMillis();
    repository.replaceSource("BENCH", "v1", lote);
    jdbc.execute("ANALYZE watchlist_entries");
    System.out.printf(
        Locale.ROOT,
        "base de %d entradas carregada em %.1fs%n",
        ENTRADAS,
        (System.currentTimeMillis() - inicio) / 1000.0);
    carregada = true;
  }

  @Test
  void oBlockingEhOrdensDeGrandezaMaisBaratoQueVarrerABase() {
    carregaUmaVez();
    Set<String> tokens = NameTokens.of("ZEFERINO QUEIROGA BITTENCOURT").values();

    // Aquecimento do caminho indexado, isolado: aquecer com findNameEntries() ANTES de medir o
    // blocking contaminava a medida — 100 mil entidades JPA no contexto de persistência fazem a
    // transação seguinte pagar dirty-checking sobre todas elas, e o custo aparecia como se fosse
    // da consulta indexada (946 ms, contra 5 ms do mesmo SQL puro).
    repository.findNameCandidates(tokens, BLOCKING);
    long tIndexado = cronometra(() -> repository.findNameCandidates(tokens, BLOCKING));

    repository.findNameEntries();
    long tVarredura = cronometra(repository::findNameEntries);

    int candidatos = repository.findNameCandidates(tokens, BLOCKING).size();
    System.out.printf(
        Locale.ROOT,
        "%n=== RESULTADO (%d entradas) ===%n"
            + "varredura completa (findAll)   : %5d ms  -> %d linhas materializadas%n"
            + "blocking por trigrama          : %5d ms  -> %d candidatos%n"
            + "ganho                          : %.1fx  (%.4f%% da base)%n%n",
        ENTRADAS,
        tVarredura,
        ENTRADAS,
        tIndexado,
        candidatos,
        tVarredura / (double) Math.max(tIndexado, 1),
        100.0 * candidatos / ENTRADAS);

    assertThat(tIndexado)
        .as(
            "blocking (%d ms) não é mais barato que a varredura (%d ms) — a migration não se paga",
            tIndexado, tVarredura)
        .isLessThan(tVarredura);
  }

  /**
   * O caso difícil, e o que realmente interessa: nome cujos tokens são os MAIS comuns da base.
   * Se o blocking degradar aqui para "quase a base inteira", o ganho médio é ilusório — em
   * produção brasileira, procurar por JOSE SILVA é o caso normal, não a exceção.
   */
  @Test
  void mesmoComTokensMuitoComunsOBlockingAindaFiltra() {
    carregaUmaVez();
    Set<String> tokens = NameTokens.of("JOSE SILVA").values();

    int candidatos = repository.findNameCandidates(tokens, BLOCKING).size();
    System.out.printf(
        Locale.ROOT,
        "pior caso ('JOSE SILVA'): %d candidatos de %d (%.1f%% da base)%n",
        candidatos,
        ENTRADAS,
        100.0 * candidatos / ENTRADAS);

    assertThat(candidatos)
        .as("com tokens comuns o blocking trouxe %d de %d — não está filtrando o caso normal",
            candidatos, ENTRADAS)
        .isLessThan(ENTRADAS);
  }

  private static long cronometra(Runnable acao) {
    long inicio = System.nanoTime();
    acao.run();
    return (System.nanoTime() - inicio) / 1_000_000;
  }
}
