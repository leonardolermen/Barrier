package com.barrier.riskengine.screening;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.commons.name.NameTokens;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.repository.interfaces.WatchlistEntryRepository;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
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
 * O blocking por trigrama <b>não pode perder</b> nenhum caso que o algoritmo casaria.
 *
 * <p><b>É o teste que autoriza a otimização.</b> Trocar o {@code findAll()} da base inteira por
 * candidatos vindos de índice é a mudança de maior ganho do repositório — e também a de maior risco,
 * porque o modo de falha é <b>silencioso</b>: um sancionado que não entra na lista de candidatos
 * nunca é comparado, o screening responde CLEAR, a avaliação aprova, e a trilha registra que o
 * controle rodou. Sem esta verificação, ganhar performance e perder cobertura seriam
 * indistinguíveis.
 *
 * <p>Usa o mesmo conjunto rotulado do {@code ScreeningRecallTest}: lá ele prova o que o
 * <b>algoritmo</b> decide; aqui, que o <b>banco</b> entrega ao algoritmo tudo de que ele precisa
 * para decidir igual. As duas metades juntas é que fecham a propriedade.
 *
 * <p>Precisa de Postgres real: {@code pg_trgm} e o operador {@code <%} são do servidor, não do JDBC.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.watchlist.refresh-cron=0 0 3 1 1 ?"
    })
@Testcontainers
class BlockingRecallIntegrationTest {

  /** O mesmo default de {@code barrier.screening.fuzzy.blocking-threshold}. */
  private static final double BLOCKING = 0.45;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired WatchlistEntryRepository repository;
  @Autowired JdbcTemplate jdbc;

  private static final List<String[]> PARES_QUE_CASAM = paresQueCasam();

  @BeforeEach
  void carregaAsListasDoConjuntoRotulado() {
    jdbc.update("DELETE FROM watchlist_entries");
    List<WatchlistRecord> entradas =
        PARES_QUE_CASAM.stream()
            .map(par -> new WatchlistRecord("TESTE", MatchType.SANCTION, null, null, par[1], "d"))
            .toList();
    repository.replaceSource("TESTE", "v1", entradas);
  }

  /**
   * Para cada par que o algoritmo casa, o candidato correspondente precisa vir do banco. Um só que
   * falte já é um falso negativo de sanção.
   */
  @Test
  void oBlockingNaoPerdeNenhumParQueOAlgoritmoCasaria() {
    List<String> perdidos = new ArrayList<>();

    for (String[] par : PARES_QUE_CASAM) {
      Set<String> tokens = NameTokens.of(par[0]).values();
      List<WatchlistRecord> candidatos = repository.findNameCandidates(tokens, BLOCKING);
      boolean encontrou = candidatos.stream().anyMatch(c -> c.name().equals(par[1]));
      if (!encontrou) {
        perdidos.add("'" + par[0] + "' nao trouxe '" + par[1] + "'");
      }
    }

    assertThat(perdidos)
        .as("o blocking descartou candidatos que o algoritmo casaria: %s", perdidos)
        .isEmpty();
  }

  /**
   * Guard antivácuo: se o blocking devolvesse a tabela inteira, o teste acima passaria sem provar
   * nada — e a otimização não teria acontecido. Aqui a exigência é o oposto: precisa <b>filtrar</b>.
   *
   * <p>O nome consultado não tem relação com nenhuma entrada carregada, então o conjunto de
   * candidatos deve ser bem menor que a base.
   */
  @Test
  void oBlockingRealmenteFiltra() {
    long total = jdbc.queryForObject("SELECT count(*) FROM watchlist_entries", Long.class);
    Set<String> tokens = NameTokens.of("ZEFERINO QUEIROGA BITTENCOURT").values();

    List<WatchlistRecord> candidatos = repository.findNameCandidates(tokens, BLOCKING);

    assertThat(total).isGreaterThan(20);
    assertThat(candidatos.size())
        .as("o blocking devolveu %d de %d entradas — não está filtrando nada", candidatos.size(), total)
        .isLessThan((int) (total / 2));
  }

  /**
   * Fail-open da V048: linha com {@code name_normalized} nulo entra como candidata sempre. É o que
   * mantém o comportamento antigo enquanto a coluna não estiver preenchida — lento, nunca cego.
   */
  @Test
  void linhaSemNomeNormalizadoEntraComoCandidata() {
    jdbc.update(
        """
        INSERT INTO watchlist_entries
               (id, source, entry_type, name, list_version, imported_at, name_normalized)
        VALUES (gen_random_uuid(), 'LEGADO', 'SANCTION', 'NOME NAO NORMALIZADO', 'v0', now(), NULL)
        """);

    List<WatchlistRecord> candidatos =
        repository.findNameCandidates(NameTokens.of("QUALQUER COISA").values(), BLOCKING);

    assertThat(candidatos).anyMatch(c -> "NOME NAO NORMALIZADO".equals(c.name()));
  }

  /** Pares MATCH do conjunto rotulado; a coluna 1 é o nome da lista. */
  private static List<String[]> paresQueCasam() {
    List<String[]> pares = new ArrayList<>();
    try (InputStream in =
            BlockingRecallIntegrationTest.class.getResourceAsStream(
                "/screening/golden-dataset.csv");
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String linha;
      while ((linha = reader.readLine()) != null) {
        if (linha.isBlank() || linha.startsWith("#") || linha.startsWith("consultado;")) {
          continue;
        }
        String[] campos = linha.split(";");
        if ("MATCH".equals(campos[2].trim())) {
          pares.add(new String[] {campos[0].trim(), campos[1].trim()});
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("conjunto rotulado ilegível", e);
    }
    return List.copyOf(pares);
  }
}
