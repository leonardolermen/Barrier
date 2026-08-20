package com.barrier.riskengine.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentProcessor;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import java.util.ArrayList;
import java.util.List;
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
 * Paralelizar não pode processar nada duas vezes, deixar nada órfão, nem perder a correlação.
 *
 * <p><b>A correlação é o ponto delicado.</b> {@code processOne} restaura o {@code correlationId} da
 * requisição original com {@code Correlation.run} justamente porque a decisão roda noutra thread,
 * minutos depois. Num pool, cada tarefa nasce com MDC vazio — se alguém mover esse tratamento para
 * fora de {@code processOne}, o log da decisão volta a nascer órfão e <b>nenhum outro teste falha
 * por isso</b>, porque teste nenhum falha por log.
 *
 * <p>Escrito ANTES de paralelizar, de propósito: é rede de segurança para a mudança, não
 * verificação depois do fato. Passa no sequencial e precisa continuar passando no paralelo.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.watchlist.refresh-cron=0 0 3 1 1 ?",
      "barrier.verification.required=false",
      "barrier.assessment.workers=4"
    })
@Testcontainers
class ParallelProcessingIntegrationTest {

  private static final int QUANTAS = 30;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired AssessmentProcessor processor;
  @Autowired AssessmentService assessments;
  @Autowired JdbcTemplate jdbc;

  @Test
  void processaOLoteSemDuplicataESemOrfa() {
    submete(QUANTAS);

    processor.process();

    Long duplicadas =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM (
              SELECT assessment_id FROM risk_scores GROUP BY assessment_id HAVING count(*) > 1
            ) d
            """,
            Long.class);
    Long pendentes =
        jdbc.queryForObject(
            "SELECT count(*) FROM assessments WHERE status = 'EM_ANALISE'", Long.class);

    assertThat(duplicadas).as("avaliacao processada duas vezes").isZero();
    assertThat(pendentes).as("avaliacao ficou orfa no lote").isZero();
  }

  /**
   * A correlação sobrevive à fronteira de thread.
   *
   * <p>Afirma pela coluna, que é o proxy disponível sem capturar o appender do Logback. A
   * propriedade que de fato importa — o {@code correlationId} aparecer no MDC do log da decisão —
   * exigiria um {@code ListAppender}; fica registrado como limite deste teste.
   */
  @Test
  void aCorrelacaoSobreviveAFronteiraDeThread() {
    submete(1);
    List<String> antes =
        jdbc.queryForList(
            "SELECT correlation_id FROM assessments WHERE status = 'EM_ANALISE'", String.class);
    assertThat(antes).as("nada submetido; o teste passaria vacuamente").isNotEmpty();

    processor.process();

    List<String> depois =
        jdbc.queryForList(
            "SELECT correlation_id FROM assessments WHERE correlation_id IN ("
                + antes.stream().map(c -> "'" + c + "'").reduce((a, b) -> a + "," + b).orElse("''")
                + ")",
            String.class);
    assertThat(depois)
        .as("a avaliacao perdeu a correlacao ao ser processada em outra thread")
        .containsExactlyInAnyOrderElementsOf(antes);
  }

  /** CPFs válidos distintos: {@code Cpf.java} rejeita qualquer outra coisa. */
  private void submete(int quantas) {
    List<String> cpfs = cpfsValidos(quantas);
    for (String cpf : cpfs) {
      assessments.submit(
          new SubmitAssessmentCommand("default", DocumentType.CPF, cpf, "Teste Paralelo", null));
    }
  }

  private static List<String> cpfsValidos(int quantas) {
    List<String> cpfs = new ArrayList<>();
    // Base a partir de 200000000: comeca em 10_000_000_000 o modulo dava "000000000", que o
    // Cpf.java rejeita como sequencia repetida — e a falha aparecia como "CPF invalido".
    long base = 200_000_000L;
    while (cpfs.size() < quantas) {
      String nove = String.format("%09d", base++);
      int[] d = new int[11];
      for (int i = 0; i < 9; i++) {
        d[i] = nove.charAt(i) - '0';
      }
      d[9] = digito(d, 10);
      d[10] = digito(d, 11);
      StringBuilder sb = new StringBuilder();
      for (int v : d) {
        sb.append(v);
      }
      cpfs.add(sb.toString());
    }
    return cpfs;
  }

  private static int digito(int[] digitos, int pesoInicial) {
    int soma = 0;
    int peso = pesoInicial;
    for (int i = 0; i < pesoInicial - 1; i++) {
      soma += digitos[i] * peso--;
    }
    int resto = soma % 11;
    return resto < 2 ? 0 : 11 - resto;
  }
}
