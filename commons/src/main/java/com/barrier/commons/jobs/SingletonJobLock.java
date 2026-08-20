package com.barrier.commons.jobs;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Faz um job {@code @Scheduled} rodar <b>uma vez por janela em todo o cluster</b>, e não uma vez
 * por réplica.
 *
 * <p>Cinco jobs do sistema são singleton por natureza e rodavam em cada pod: o
 * {@code WatchlistImporter} baixaria o ZIP da OFAC/CGU/ONU cinco vezes; o
 * {@code PeriodicReassessmentJob} transformaria o teto de 200 por execução em 1000 por noite,
 * anulando justamente o controle de custo que o teto existe para dar.
 *
 * <p><b>Por que lease e não {@code pg_try_advisory_lock}.</b> O advisory lock é ligado à
 * <i>sessão</i>: com pool de conexões seria preciso fixar a conexão pelo job inteiro para poder
 * soltá-lo, e a variante {@code _xact_} só solta no fim da transação — o que manteria uma
 * transação aberta durante os minutos de download de uma importação, segurando vacuum. O lease é o
 * mesmo padrão da outbox (V025) e do claim de avaliações, e tem a propriedade que importa às 3 da
 * manhã: <b>pod que morre no meio do job não deixa lock preso</b>, o lease só vence.
 *
 * <p><b>Duas durações, e a segunda é a que não é óbvia.</b>
 *
 * <ul>
 *   <li>{@code lockAtMostFor} — teto de segurança. Se o pod morrer no meio, é quando o job volta a
 *       ser reivindicável. Dimensionar com folga sobre o pior tempo observado.
 *   <li>{@code lockAtLeastFor} — piso. Ao terminar, o lease <b>não</b> é liberado antes disto.
 *       Sem o piso, um job de 2 minutos libera o lock e uma réplica cujo cron disparou atrasado
 *       (clock skew, scheduler ocupado, pod que subiu depois) reivindica e <b>roda a mesma janela
 *       de novo</b> — 400 reavaliações pagas em vez de 200, com o teto intacto no código e
 *       violado na prática. O piso é o que transforma "exclusão mútua durante a execução" em
 *       "uma vez por janela".
 * </ul>
 *
 * <p><b>Isto não é substituto de idempotência.</b> Job que ultrapassa o próprio
 * {@code lockAtMostFor} pode ser acompanhado por outra réplica. Manter os jobs idempotentes de
 * qualquer forma — o que já vale, porque Kafka é at-least-once e a importação é substituição por
 * fonte.
 */
@Component
public class SingletonJobLock {

  private static final Logger log = LoggerFactory.getLogger(SingletonJobLock.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final String instanceId;

  public SingletonJobLock(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.instanceId = resolveInstanceId();
  }

  /**
   * Executa {@code job} se esta réplica conseguir o lease; caso contrário não faz nada.
   *
   * <p>A exceção do job <b>sobe</b>, depois de ajustar o lease: engoli-la aqui transformaria falha
   * de importação em silêncio, que é o modo de falha que o {@code WatchlistImportStatus} existe
   * para eliminar. Quem chama decide o tratamento.
   *
   * @return {@code true} se esta réplica executou
   */
  public boolean runIfLeader(
      String jobName, Duration lockAtLeastFor, Duration lockAtMostFor, Runnable job) {
    Instant acquiredAt = Instant.now(clock);
    if (!tryAcquire(jobName, acquiredAt, lockAtMostFor)) {
      log.debug("Job {} já reivindicado por outra réplica nesta janela; pulando", jobName);
      return false;
    }
    log.info("Job {} reivindicado por {}", jobName, instanceId);
    try {
      job.run();
      return true;
    } finally {
      releaseNotBefore(jobName, acquiredAt.plus(lockAtLeastFor));
    }
  }

  /**
   * {@code REQUIRES_NEW} porque a reivindicação precisa estar <b>commitada</b> antes de o job
   * começar: dentro da transação de quem chama, duas réplicas poderiam ler o mesmo estado e as
   * duas se acharem líderes. Mesmo raciocínio do {@code IdempotencyService}.
   *
   * <p>O {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE} é uma operação atômica só: quem perder
   * a corrida afeta zero linhas, sem {@code SELECT FOR UPDATE} e sem risco de dois vencedores.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  boolean tryAcquire(String jobName, Instant now, Duration lockAtMostFor) {
    return jdbc.update(
            """
            INSERT INTO job_locks (job_name, locked_until, locked_by, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (job_name) DO UPDATE
               SET locked_until = EXCLUDED.locked_until,
                   locked_by    = EXCLUDED.locked_by,
                   updated_at   = EXCLUDED.updated_at
             WHERE job_locks.locked_until <= ?
            """,
            jobName,
            Timestamp.from(now.plus(lockAtMostFor)),
            instanceId,
            Timestamp.from(now),
            Timestamp.from(now))
        > 0;
  }

  /**
   * Encurta o lease para {@code naoAntesDe}, nunca para o passado — é o piso ({@code
   * lockAtLeastFor}) que impede outra réplica de reexecutar a mesma janela.
   *
   * <p>O {@code GREATEST} evita o caso oposto: um job que demorou mais que o piso não pode ter o
   * lease <b>estendido</b> na liberação, senão a próxima janela legítima ficaria bloqueada.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void releaseNotBefore(String jobName, Instant naoAntesDe) {
    Instant now = Instant.now(clock);
    jdbc.update(
        """
        UPDATE job_locks
           SET locked_until = LEAST(locked_until, GREATEST(?::timestamptz, ?::timestamptz)),
               updated_at   = ?::timestamptz
         WHERE job_name = ? AND locked_by = ?
        """,
        Timestamp.from(naoAntesDe),
        Timestamp.from(now),
        Timestamp.from(now),
        jobName,
        instanceId);
  }

  /** Hostname do pod no Kubernetes; responde "qual réplica está rodando isto". */
  private static String resolveInstanceId() {
    String hostname = System.getenv("HOSTNAME");
    if (hostname != null && !hostname.isBlank()) {
      return hostname;
    }
    try {
      return java.net.InetAddress.getLocalHost().getHostName();
    } catch (java.net.UnknownHostException e) {
      return "desconhecido-" + java.util.UUID.randomUUID();
    }
  }
}
