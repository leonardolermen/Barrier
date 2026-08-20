package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.watchlist.interfaces.WatchlistSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
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
 * A cobertura de watchlist precisa ser visível para <b>qualquer</b> réplica, tenha ela importado ou
 * não.
 *
 * <p><b>É a armadilha da entrega do lock de job.</b> Com o {@code WatchlistImporter} virando
 * singleton (V045), quatro das cinco réplicas nunca importam. Enquanto o status vivia num
 * {@code ConcurrentHashMap} por instância, essas quatro nasceriam com cobertura vazia e a
 * {@code ScreeningCoverageRiskRule} mandaria <b>100% das avaliações que elas atendessem</b> para
 * revisão manual — um incidente maior que o problema que o lock resolve. Por isso V045 e V046 são
 * a mesma entrega.
 *
 * <p>O teste simula duas réplicas com <b>duas instâncias distintas</b> de
 * {@link WatchlistImportStatus} sobre o mesmo banco: é o que a memória compartilhada de um único
 * objeto esconderia.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.watchlist.refresh-cron=0 0 3 1 1 ?"
    })
@Testcontainers
class WatchlistCoverageSharedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;

  private WatchlistImportStatus replica() {
    return new WatchlistImportStatus(jdbc, clock, Duration.ofHours(48));
  }

  @Test
  void replicaQueNaoImportouEnxergaACoberturaDeQuemImportou() {
    WatchlistImportStatus lider = replica();
    WatchlistImportStatus seguidora = replica();

    lider.recordSuccess(fonte("ofac-teste", MatchType.SANCTION), 1_234);

    assertThat(seguidora.coverage())
        .as("réplica que não importou ficaria sem cobertura e mandaria tudo para revisão manual")
        .contains(MatchType.SANCTION);
  }

  /**
   * O bug que já existia <b>antes</b> do lock: com status por instância, uma réplica cujo download
   * falhou se dava por descoberta — mesmo com a tabela {@code watchlist_entries}, que é
   * compartilhada, integralmente populada pelas outras.
   */
  @Test
  void falhaEmUmaReplicaNaoApagaACoberturaDasOutras() {
    WatchlistImportStatus lider = replica();
    WatchlistImportStatus azarada = replica();

    lider.recordSuccess(fonte("cgu-teste", MatchType.DEBARMENT), 500);
    azarada.recordFailure(fonte("cgu-teste", MatchType.DEBARMENT), "timeout no download");

    assertThat(lider.coverage())
        .as("a base anterior segue utilizável: falha não pode zerar a cobertura")
        .contains(MatchType.DEBARMENT);
    assertThat(azarada.of("cgu-teste"))
        .hasValueSatisfying(status -> assertThat(status.lastError()).isEqualTo("timeout no download"));
  }

  /** Importação vencida não conta — lista de seis meses atrás não cobre sanção do mês passado. */
  @Test
  void coberturaVencidaNaoConta() {
    WatchlistImportStatus curtissimoPrazo =
        new WatchlistImportStatus(jdbc, clock, Duration.ZERO.minusSeconds(1));

    curtissimoPrazo.recordSuccess(fonte("un-teste", MatchType.SANCTION), 10);

    assertThat(curtissimoPrazo.coverage()).doesNotContain(MatchType.SANCTION);
  }

  private static WatchlistSource fonte(String nome, MatchType tipo) {
    return new WatchlistSource() {
      @Override
      public String source() {
        return nome;
      }

      @Override
      public Set<MatchType> provides() {
        return Set.of(tipo);
      }

      @Override
      public WatchlistBatch fetch() {
        throw new UnsupportedOperationException("não usado neste teste");
      }
    };
  }
}
