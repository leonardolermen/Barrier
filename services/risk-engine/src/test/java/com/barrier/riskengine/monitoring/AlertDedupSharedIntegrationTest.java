package com.barrier.riskengine.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assessment.repository.interfaces.AssessmentRepository;
import com.barrier.commons.jobs.SingletonJobLock;
import com.barrier.riskengine.monitoring.domain.Alert;
import com.barrier.riskengine.monitoring.service.AlertEvaluator;
import com.barrier.riskengine.monitoring.service.PipelineSnapshot;
import com.barrier.riskengine.monitoring.service.interfaces.AlertNotifier;
import com.barrier.riskengine.monitoring.service.interfaces.AlertRule;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * O dedup de alerta precisa valer <b>entre réplicas</b>, não dentro de uma.
 *
 * <p><b>Como o bug apareceu.</b> Depois de pôr o lock no {@code AlertEvaluator}, uma medição em
 * cluster de 5 réplicas mostrou 17 execuções em 180s — o lock funcionava, mas a <b>liderança
 * rotacionava</b> entre os pods (o piso do lease é zero para este job, de propósito). Com o dedup
 * num {@code HashMap} de instância, o pod A notificava e o pod B, líder do ciclo seguinte,
 * notificava de novo o mesmo código com o mapa vazio. O controle existia, tinha comentário
 * explicando, e não deduplicava nada.
 *
 * <p>Mesmo defeito que a cobertura de watchlist tinha: <b>estado do cluster guardado na memória de
 * uma instância</b>. E não havia teste algum do {@code AlertEvaluator} — só das regras —, que é por
 * onde passou.
 *
 * <p>Duas instâncias distintas de {@code AlertEvaluator} sobre o mesmo banco simulam os dois pods;
 * uma instância só esconderia exatamente o que se quer provar.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.watchlist.refresh-cron=0 0 3 1 1 ?"
    })
@Testcontainers
class AlertDedupSharedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired AssessmentRepository repository;
  @Autowired SingletonJobLock jobLock;

  private final List<String> notificados = new CopyOnWriteArrayList<>();

  @Test
  void oMesmoAlertaNaoEhNotificadoDuasVezesPorReplicasDiferentes() {
    AlertEvaluator podA = evaluator("dedup_entre_replicas", Duration.ofHours(1));
    AlertEvaluator podB = evaluator("dedup_entre_replicas", Duration.ofHours(1));

    podA.evaluate();
    podB.evaluate();

    assertThat(notificados)
        .as("réplica diferente renotificou o mesmo código — o dedup não é compartilhado")
        .hasSize(1);
  }

  /** Passado o intervalo, o alerta volta a ser notificável — senão um incidente longo some. */
  @Test
  void voltaANotificarDepoisDoIntervalo() {
    evaluator("dedup_expirado", Duration.ZERO).evaluate();
    evaluator("dedup_expirado", Duration.ZERO).evaluate();

    assertThat(notificados).hasSize(2);
  }

  /**
   * Código distinto por teste: o lease vive no <b>banco</b> e sobrevive entre métodos: com o mesmo
   * código, o piso de 1h do primeiro teste calaria o segundo, e a falha pareceria bug do dedup.
   */
  private AlertEvaluator evaluator(String code, Duration repeatInterval) {
    return new AlertEvaluator(
        repository,
        List.of(new AlertaSempre(code)),
        List.of((AlertNotifier) alert -> notificados.add(alert.code())),
        jobLock,
        7,
        repeatInterval);
  }

  /** Regra que sempre dispara: o alvo do teste é o dedup, não a decisão da regra. */
  private record AlertaSempre(String code) implements AlertRule {
    @Override
    public Optional<Alert> evaluate(PipelineSnapshot snapshot) {
      return Optional.of(new Alert(code, Alert.Severity.WARNING, "alerta de teste", "evidencia"));
    }
  }
}
