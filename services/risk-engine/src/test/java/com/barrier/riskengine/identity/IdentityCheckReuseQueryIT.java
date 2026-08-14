package com.barrier.riskengine.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.repository.interfaces.IdentityCheckRepository;
import java.time.Duration;
import java.time.Instant;
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
 * Prova, contra Postgres real, {@code IdentityCheckRepositoryImpl#findReusable} (migration V040):
 * os quatro filtros que decidem se uma verificação antiga pode ser reaproveitada em vez de pagar
 * outra consulta ao bureau — tenant, janela de tempo, nome e "não é ele mesmo um reuso".
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class IdentityCheckReuseQueryIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired IdentityCheckRepository repository;

  private IdentityCheck salvar(
      String tenantId,
      String documentType,
      String documentDigits,
      String name,
      IdentityStatus status,
      Instant checkedAt) {
    IdentityCheck check =
        IdentityCheck.create(
            "aval-" + Instant.now().toEpochMilli() + "-" + Math.random(),
            tenantId,
            documentType,
            documentDigits,
            name,
            status,
            "bigboost",
            "titular regular",
            "query-" + Math.random(),
            "{}");
    check = new IdentityCheck(
        check.id(),
        check.assessmentId(),
        check.tenantId(),
        check.documentType(),
        check.documentDigits(),
        check.name(),
        check.status(),
        check.provider(),
        check.detail(),
        checkedAt,
        check.providerReference(),
        check.rawResponse(),
        check.reusedFromId());
    return repository.save(check);
  }

  @Test
  void naoReaproveitaCheckDeOutroTenant() {
    salvar("tenant-a", "CPF", "11144477735", "MARIA SILVA", IdentityStatus.VERIFIED, Instant.now());

    assertThat(
            repository.findReusable(
                "tenant-b",
                "CPF",
                "11144477735",
                "MARIA SILVA",
                Instant.now().minus(Duration.ofHours(24))))
        .isEmpty();
  }

  @Test
  void naoReaproveitaCheckForaDaJanela() {
    salvar(
        "tenant-a",
        "CPF",
        "11144477736",
        "MARIA SILVA",
        IdentityStatus.VERIFIED,
        Instant.now().minus(Duration.ofHours(30)));

    assertThat(
            repository.findReusable(
                "tenant-a",
                "CPF",
                "11144477736",
                "MARIA SILVA",
                Instant.now().minus(Duration.ofHours(24))))
        .isEmpty();
  }

  @Test
  void naoReaproveitaCheckDeNomeDiferenteNoMesmoDocumento() {
    salvar("tenant-a", "CPF", "11144477737", "MARIA SILVA", IdentityStatus.VERIFIED, Instant.now());

    assertThat(
            repository.findReusable(
                "tenant-a",
                "CPF",
                "11144477737",
                "MARIA SILVA SANTOS",
                Instant.now().minus(Duration.ofHours(24))))
        .isEmpty();
  }

  @Test
  void reaproveitaOCheckMaisRecenteDentroDaJanela() {
    salvar(
        "tenant-a",
        "CPF",
        "11144477738",
        "MARIA SILVA",
        IdentityStatus.VERIFIED,
        Instant.now().minus(Duration.ofHours(10)));
    IdentityCheck recente =
        salvar(
            "tenant-a",
            "CPF",
            "11144477738",
            "MARIA SILVA",
            IdentityStatus.VERIFIED,
            Instant.now().minus(Duration.ofHours(1)));

    assertThat(
            repository.findReusable(
                "tenant-a",
                "CPF",
                "11144477738",
                "MARIA SILVA",
                Instant.now().minus(Duration.ofHours(24))))
        .map(IdentityCheck::id)
        .contains(recente.id());
  }
}
