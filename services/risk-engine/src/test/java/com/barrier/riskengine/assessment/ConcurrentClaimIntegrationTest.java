package com.barrier.riskengine.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.repository.interfaces.AssessmentRepository;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Prova, contra Postgres real, que a reivindicação de avaliações é exclusiva.
 *
 * <p>Este é o teste que fecha o achado de duplicação: antes, o poller lia as pendentes sem lock,
 * então duas réplicas processavam a mesma avaliação, consumiam o bureau duas vezes e emitiam dois
 * eventos com {@code eventId} distintos — a idempotência do webhook não filtrava, e o cliente
 * recebia dois callbacks possivelmente contraditórios, sem critério de desempate.
 *
 * <p>Precisa de banco de verdade: {@code FOR UPDATE SKIP LOCKED} é comportamento do Postgres, e um
 * mock não teria como demonstrá-lo.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class ConcurrentClaimIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  private static final Duration LEASE = Duration.ofMinutes(5);

  @Autowired AssessmentService assessmentService;
  @Autowired AssessmentRepository repository;

  /** CPFs distintos e com dígito verificador válido — o domínio recusa qualquer outro. */
  private static final List<String> CPFS =
      List.of("111.444.777-35", "529.982.247-25", "123.456.789-09", "987.654.321-00");

  private void submeterPendentes() {
    CPFS.forEach(
        cpf ->
            assessmentService.submit(
                new SubmitAssessmentCommand("default", DocumentType.CPF, cpf, "Fulano " + cpf)));
  }

  @Test
  void avaliacaoReivindicadaNaoEDevolvidaDeNovoEnquantoALeaseVale() {
    submeterPendentes();

    List<AssessmentId> primeira = repository.claimPending(10, LEASE);
    List<AssessmentId> segunda = repository.claimPending(10, LEASE);

    assertThat(primeira).hasSizeGreaterThanOrEqualTo(CPFS.size());
    assertThat(segunda).isEmpty();
  }

  /** Lease expirada devolve a avaliação à fila — instância que morreu no meio não a prende. */
  @Test
  void leaseExpiradaDevolveAAvaliacaoParaAFila() {
    submeterPendentes();

    repository.claimPending(10, LEASE);
    List<AssessmentId> aposExpirar = repository.claimPending(10, Duration.ZERO);

    assertThat(aposExpirar).hasSizeGreaterThanOrEqualTo(CPFS.size());
  }

  /**
   * O caso que importa: duas "réplicas" reivindicando ao mesmo tempo têm de receber conjuntos
   * <b>disjuntos</b>. Sem {@code SKIP LOCKED} as duas leriam as mesmas linhas.
   */
  @Test
  void reivindicacoesConcorrentesNaoSeSobrepoem() throws Exception {
    submeterPendentes();

    Callable<List<AssessmentId>> replica = () -> repository.claimPending(10, LEASE);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<List<AssessmentId>>> resultados = pool.invokeAll(List.of(replica, replica));

      List<AssessmentId> todas = new ArrayList<>();
      for (Future<List<AssessmentId>> resultado : resultados) {
        todas.addAll(resultado.get());
      }

      assertThat(todas).doesNotHaveDuplicates();
      assertThat(todas).hasSizeGreaterThanOrEqualTo(CPFS.size());
    } finally {
      pool.shutdownNow();
    }
  }
}
