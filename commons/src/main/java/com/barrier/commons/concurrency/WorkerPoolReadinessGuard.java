package com.barrier.commons.concurrency;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Falha a subida quando o teto de trabalhadores paralelos não cabe no pool de conexões.
 *
 * <p>A amarra {@code workers <= maximum-pool-size - RESERVA} existia desde a paralelização do
 * pipeline, escrita em <b>comentário</b> nos dois {@code application.yml} e verificada em lugar
 * nenhum. {@code ASSESSMENT_WORKERS=12} com {@code DB_POOL_SIZE=8} subia normalmente e degradava em
 * timeout ao obter conexão — e o próprio comentário admitia que a mensagem de erro não distingue
 * "banco lento" de "teto mal configurado". Invariante em comentário é invariante que não existe.
 *
 * <p>É de <b>startup</b> e não de runtime porque a alternativa é descobrir em produção, sob carga,
 * com o sintoma apontando para o lugar errado: quem investiga um {@code connection timeout} olha o
 * banco, não o valor de uma variável de ambiente. Mesmo padrão dos demais guards da base
 * ({@code WatchlistReadinessGuard}, {@code CnpjBureauReadinessGuard}) — checagem barata na subida
 * em vez de diagnóstico caro depois.
 *
 * <p><b>A reserva não é folga arbitrária</b>: são as conexões de quem <i>não</i> passa pelo
 * semáforo e ainda assim precisa do banco — a ingestão HTTP, o relay de outbox, os {@code
 * @Scheduled}. Sem ela, o lote no teto tomaria o pool inteiro e a API pararia de responder
 * enquanto o processamento vai bem, obrigado.
 *
 * <p>Vive no {@code commons} porque o que se compartilha é a <b>invariante</b> (e a mensagem que
 * ela imprime), não a aritmética — que é trivial. Cada serviço declara o seu bean com o próprio
 * nome de propriedade, para o erro citar exatamente a variável que o operador tem de mexer.
 */
public class WorkerPoolReadinessGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(WorkerPoolReadinessGuard.class);

  private final DataSource dataSource;
  private final String propriedade;
  private final int workers;
  private final int reserva;

  /**
   * @param propriedade nome completo da propriedade de workers, como aparece no YAML — é ela que a
   *     mensagem de erro manda ajustar
   * @param reserva conexões que precisam sobrar para quem não passa pelo semáforo
   */
  public WorkerPoolReadinessGuard(
      DataSource dataSource, String propriedade, int workers, int reserva) {
    this.dataSource = dataSource;
    this.propriedade = propriedade;
    this.workers = workers;
    this.reserva = reserva;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (workers < 1) {
      throw new IllegalStateException(
          propriedade + " = " + workers + ": sem trabalhador nenhum a fila nunca drena.");
    }
    // Pool que não é Hikari não expõe teto por um contrato comum. Avisar e seguir é melhor que
    // adivinhar: um guard que erra o diagnóstico é pior que guard nenhum.
    if (!(dataSource instanceof HikariDataSource hikari)) {
      log.warn(
          "DataSource não é HikariDataSource ({}); a amarra entre {} e o tamanho do pool não pôde"
              + " ser verificada.",
          dataSource.getClass().getName(),
          propriedade);
      return;
    }
    int pool = hikari.getMaximumPoolSize();
    int teto = pool - reserva;
    if (workers > teto) {
      throw new IllegalStateException(
          propriedade
              + " = "
              + workers
              + ", mas o pool de conexões tem "
              + pool
              + " e "
              + reserva
              + " precisam sobrar para a ingestão e os jobs — o teto é "
              + teto
              + ". Nesta configuração o lote no limite toma o pool inteiro e o sintoma é timeout ao"
              + " obter conexão, que parece problema de banco e não de configuração. Reduza "
              + propriedade
              + " para no máximo "
              + teto
              + ", ou suba spring.datasource.hikari.maximum-pool-size para pelo menos "
              + (workers + reserva)
              + " — lembrando que o pool é por POD e o total somado precisa caber no"
              + " max_connections do Postgres.");
    }
    log.info(
        "{} = {} dentro do teto de {} (pool {} - {} reservadas).", propriedade, workers, teto, pool, reserva);
  }
}
