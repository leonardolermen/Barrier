package com.barrier.riskengine.assurance;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.domain.DivergentField;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * Prova, contra Postgres real, o SQL de {@code AssuranceCheckRepositoryImpl} — o mapeamento das
 * migrations V035/V036/V037 nunca tinha sido exercitado contra banco de verdade.
 *
 * <p>Dois pontos cobertos aqui fecham gaps herdados de revisões anteriores deste plano: o
 * round-trip das três colunas de consentimento (V036) e, principalmente, o round-trip de
 * {@code divergent_fields} (V037) com conjunto <b>não vazio</b> — a coluna nasceu para consertar
 * um Critical de persistência (marcador anexado sem limite a {@code detail VARCHAR(400)}) e,
 * até este teste, nenhum Testcontainers gravava e relia {@code "NAME"}/{@code "BIRTH_DATE"} de
 * verdade.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class AssuranceCheckRepositoryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired AssuranceCheckRepository repository;
  @Autowired SubjectService subjectService;
  @Autowired JdbcTemplate jdbc;

  /** Cada teste usa um CPF/tenant próprios para não colidir com dado de outro método. */
  private UUID novoSubject(String cpf) {
    Subject subject = subjectService.findOrCreate("CPF", cpf, "Fulano de Tal");
    subjectService.link("default", subject.id());
    return subject.id();
  }

  private void garanteTenant(String tenantId) {
    jdbc.update(
        "INSERT INTO tenants (id, name, active) VALUES (?, ?, true) ON CONFLICT (id) DO NOTHING",
        tenantId,
        tenantId);
  }

  private AssuranceCheck checagem(
      UUID subjectId,
      String tenantId,
      AssuranceKind kind,
      Integer score,
      String providerReference,
      Set<DivergentField> divergences,
      Instant checkedAt,
      AssuranceConsent consent) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        subjectId,
        tenantId,
        kind,
        AssuranceOutcome.PASS,
        score,
        "provedor-teste",
        providerReference,
        "modelo-v3",
        "sha256-hash-de-teste",
        "aprovado pelo provedor",
        divergences,
        checkedAt,
        consent);
  }

  // --- round-trip completo (todos os campos, inclusive nulos legítimos) --------------------

  /**
   * A BrasilAPI (bureau de teste desta frente) não fornece {@code providerReference}; o score
   * também é nulo sempre que o provedor só devolve desfecho. Os dois nulos têm de sobreviver ao
   * round-trip sem virar zero/string vazia.
   */
  @Test
  void roundTripPreservaTodosOsCamposInclusiveNulosLegitimos() {
    UUID subjectId = novoSubject("52998224725");
    Instant checkedAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS);
    AssuranceCheck original =
        checagem(
            subjectId,
            "default",
            AssuranceKind.BIOMETRIC,
            null,
            null,
            Set.of(),
            checkedAt,
            null);

    repository.save(original);

    AssuranceCheck lido =
        repository.findLatest(subjectId, "default", AssuranceKind.BIOMETRIC).orElseThrow();

    assertThat(lido.id()).isEqualTo(original.id());
    assertThat(lido.subjectId()).isEqualTo(subjectId);
    assertThat(lido.tenantId()).isEqualTo("default");
    assertThat(lido.kind()).isEqualTo(AssuranceKind.BIOMETRIC);
    assertThat(lido.outcome()).isEqualTo(AssuranceOutcome.PASS);
    assertThat(lido.score()).isNull();
    assertThat(lido.provider()).isEqualTo("provedor-teste");
    assertThat(lido.providerReference()).isNull();
    assertThat(lido.algorithmVersion()).isEqualTo("modelo-v3");
    assertThat(lido.submittedHash()).isEqualTo("sha256-hash-de-teste");
    assertThat(lido.detail()).isEqualTo("aprovado pelo provedor");
    assertThat(lido.checkedAt()).isEqualTo(checkedAt);
    assertThat(lido.consent()).isNull();
  }

  @Test
  void roundTripPreservaScoreEProviderReferenceQuandoPresentes() {
    UUID subjectId = novoSubject("11144477735");
    AssuranceCheck original =
        checagem(
            subjectId,
            "default",
            AssuranceKind.DOCUMENT,
            87,
            "ref-provedor-123",
            Set.of(),
            Instant.now(),
            null);

    repository.save(original);

    AssuranceCheck lido =
        repository.findLatest(subjectId, "default", AssuranceKind.DOCUMENT).orElseThrow();

    assertThat(lido.score()).isEqualTo(87);
    assertThat(lido.providerReference()).isEqualTo("ref-provedor-123");
  }

  // --- V036: consentimento --------------------------------------------------------------------

  /**
   * As três colunas de consentimento nasceram no INSERT/RowMapper escritas à mão e nunca foram
   * verificadas contra Postgres. Se qualquer uma delas trocar de posição no INSERT (ex.: purpose
   * no lugar de reference), este teste falha — os três valores são distintos de propósito.
   */
  @Test
  void roundTripPreservaAsTresColunasDeConsentimento() {
    UUID subjectId = novoSubject("10011111178");
    Instant grantedAt = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MICROS);
    AssuranceConsent consent =
        new AssuranceConsent("consent-ref-42", "verificação de identidade", grantedAt);
    AssuranceCheck original =
        checagem(
            subjectId,
            "default",
            AssuranceKind.DOCUMENT,
            90,
            "ref-1",
            Set.of(),
            Instant.now(),
            consent);

    repository.save(original);

    AssuranceCheck lido =
        repository.findLatest(subjectId, "default", AssuranceKind.DOCUMENT).orElseThrow();

    assertThat(lido.consent()).isNotNull();
    assertThat(lido.consent().reference()).isEqualTo("consent-ref-42");
    assertThat(lido.consent().purpose()).isEqualTo("verificação de identidade");
    assertThat(lido.consent().grantedAt()).isEqualTo(grantedAt);
  }

  /**
   * Linha legada anterior à V036 (ou verificação sem consentimento anexado): as três colunas são
   * nullable de propósito, e o RowMapper tem de devolver {@code consent() == null} em vez de um
   * {@code AssuranceConsent} com campos nulos por dentro.
   */
  @Test
  void linhaComConsentimentoNuloLeGeraConsentNulo() {
    UUID subjectId = novoSubject("10022222227");
    AssuranceCheck original =
        checagem(
            subjectId,
            "default",
            AssuranceKind.BIOMETRIC,
            70,
            null,
            Set.of(),
            Instant.now(),
            null);

    repository.save(original);

    AssuranceCheck lido =
        repository.findLatest(subjectId, "default", AssuranceKind.BIOMETRIC).orElseThrow();

    assertThat(lido.consent()).isNull();
  }

  // --- V037: divergent_fields ------------------------------------------------------------------

  /** O caso que nenhum teste anterior cobria: um único campo divergente sobrevive ao round-trip. */
  @Test
  void roundTripPreservaUmCampoDivergente() {
    UUID subjectId = novoSubject("10033333386");
    AssuranceCheck original =
        checagem(
            subjectId,
            "default",
            AssuranceKind.DOCUMENT,
            60,
            "ref-1",
            Set.of(DivergentField.NAME),
            Instant.now(),
            null);

    repository.save(original);

    AssuranceCheck lido =
        repository.findLatest(subjectId, "default", AssuranceKind.DOCUMENT).orElseThrow();

    assertThat(lido.divergences()).containsExactly(DivergentField.NAME);
  }

  /** E os dois campos juntos — prova que o join por vírgula no INSERT e o split na leitura batem. */
  @Test
  void roundTripPreservaOsDoisCamposDivergentes() {
    UUID subjectId = novoSubject("10044444435");
    AssuranceCheck original =
        checagem(
            subjectId,
            "default",
            AssuranceKind.DOCUMENT,
            40,
            "ref-1",
            Set.of(DivergentField.NAME, DivergentField.BIRTH_DATE),
            Instant.now(),
            null);

    repository.save(original);

    AssuranceCheck lido =
        repository.findLatest(subjectId, "default", AssuranceKind.DOCUMENT).orElseThrow();

    assertThat(lido.divergences())
        .containsExactlyInAnyOrder(DivergentField.NAME, DivergentField.BIRTH_DATE);
  }

  @Test
  void roundTripComConjuntoVazioDevolveSetVazio() {
    UUID subjectId = novoSubject("10055555594");
    AssuranceCheck original =
        checagem(
            subjectId,
            "default",
            AssuranceKind.DOCUMENT,
            95,
            "ref-1",
            Set.of(),
            Instant.now(),
            null);

    repository.save(original);

    AssuranceCheck lido =
        repository.findLatest(subjectId, "default", AssuranceKind.DOCUMENT).orElseThrow();

    assertThat(lido.divergences()).isEmpty();
  }

  /**
   * Linha anterior à V037: {@code divergent_fields} nem existia nessas linhas, e a coluna virou
   * {@code NULL} para elas — nunca vazio-string. Vazio e nulo têm de colapsar no mesmo valor na
   * leitura, senão o motor de risco veria uma diferença que não existe.
   */
  @Test
  void colunaNulaColapsaNoMesmoValorQueConjuntoVazio() {
    UUID subjectId = novoSubject("10066666643");
    UUID id = UUID.randomUUID();
    Instant checkedAt = Instant.now();
    // insere sem passar por divergent_fields, como uma linha gravada antes da V037 estaria —
    // simula a coluna nula, não uma string vazia.
    jdbc.update(
        "INSERT INTO identity_assurance_checks"
            + " (id, subject_id, tenant_id, kind, outcome, score, provider, provider_reference,"
            + " algorithm_version, submitted_hash, detail, checked_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        subjectId,
        "default",
        AssuranceKind.DOCUMENT.name(),
        AssuranceOutcome.PASS.name(),
        85,
        "provedor-teste",
        "ref-legado",
        "modelo-v1",
        "hash-legado",
        "linha anterior à V037",
        Timestamp.from(checkedAt));

    AssuranceCheck lido =
        repository.findLatest(subjectId, "default", AssuranceKind.DOCUMENT).orElseThrow();

    assertThat(lido.divergences()).isEmpty();
  }

  // --- findLatest: o mais recente por (subject, tenant, kind) ----------------------------------

  /**
   * O índice é {@code (subject_id, tenant_id, kind, checked_at DESC)} — refazer a biometria depois
   * de uma falha é o fluxo normal, e a decisão tem de olhar a última tentativa, não a primeira que
   * o Postgres decidir devolver sem o {@code ORDER BY}.
   *
   * <p>A checagem mais recente é gravada <b>primeiro</b>, e a mais antiga depois, de propósito:
   * numa tabela pequena recém-populada o Postgres tende a devolver as linhas na ordem física de
   * inserção, então se a ordem de inserção coincidisse com {@code checked_at DESC} este teste
   * passaria mesmo sem o {@code ORDER BY} no SQL — não provaria nada. Gravando fora de ordem, só
   * o {@code ORDER BY checked_at DESC} explica {@code findLatest} devolver a mais recente.
   */
  @Test
  void findLatestDevolveOMaisRecenteQuandoHaVariosDoMesmoTipo() {
    UUID subjectId = novoSubject("10077777700");
    Instant maisAntigo = Instant.now().minus(2, ChronoUnit.DAYS);
    Instant maisRecente = Instant.now();

    // grava a mais RECENTE primeiro — fora da ordem cronológica e fora da ordem que o SQL tem de
    // devolver, para que só o ORDER BY explique o resultado, não a ordem física de inserção.
    repository.save(
        checagem(
            subjectId,
            "default",
            AssuranceKind.DOCUMENT,
            99,
            "ref-novo",
            Set.of(),
            maisRecente,
            null));
    repository.save(
        checagem(
            subjectId,
            "default",
            AssuranceKind.DOCUMENT,
            30,
            "ref-antigo",
            Set.of(DivergentField.NAME),
            maisAntigo,
            null));

    AssuranceCheck lido =
        repository.findLatest(subjectId, "default", AssuranceKind.DOCUMENT).orElseThrow();

    assertThat(lido.providerReference()).isEqualTo("ref-novo");
    assertThat(lido.score()).isEqualTo(99);
  }

  @Test
  void findLatestNaoDevolveTipoDiferente() {
    UUID subjectId = novoSubject("10088888851");
    repository.save(
        checagem(
            subjectId,
            "default",
            AssuranceKind.BIOMETRIC,
            50,
            "ref-bio",
            Set.of(),
            Instant.now(),
            null));

    Optional<AssuranceCheck> lido =
        repository.findLatest(subjectId, "default", AssuranceKind.DOCUMENT);

    assertThat(lido).isEmpty();
  }

  // --- findAll: isolamento entre tenants ---------------------------------------------------------

  /**
   * O critério de pronto do plano de auditoria para isolamento de tenant: apague a checagem de
   * tenant do repositório (troque o {@code AND tenant_id = ?} por nada, ou o parâmetro errado) e
   * este teste tem de ficar vermelho. Mesmo subject, dois tenants: {@code findAll} de A não pode
   * devolver a linha de B.
   */
  @Test
  void findAllNaoVazaCheckDeOutroTenant() {
    garanteTenant("parceiro-assurance-repo-b");
    UUID subjectId = novoSubject("10099999900");
    subjectService.link("parceiro-assurance-repo-b", subjectId);

    repository.save(
        checagem(
            subjectId,
            "default",
            AssuranceKind.DOCUMENT,
            10,
            "ref-a",
            Set.of(),
            Instant.now(),
            null));
    repository.save(
        checagem(
            subjectId,
            "parceiro-assurance-repo-b",
            AssuranceKind.DOCUMENT,
            20,
            "ref-b",
            Set.of(),
            Instant.now(),
            null));

    List<AssuranceCheck> deA = repository.findAll(subjectId, "default");

    assertThat(deA).hasSize(1);
    assertThat(deA.get(0).providerReference()).isEqualTo("ref-a");
    assertThat(deA.get(0).tenantId()).isEqualTo("default");
  }

  @Test
  void findAllDevolveTodoOHistoricoDoTenantCorreto() {
    UUID subjectId = novoSubject("10111111013");
    repository.save(
        checagem(
            subjectId,
            "default",
            AssuranceKind.DOCUMENT,
            10,
            "ref-1",
            Set.of(),
            Instant.now().minusSeconds(10),
            null));
    repository.save(
        checagem(
            subjectId,
            "default",
            AssuranceKind.BIOMETRIC,
            20,
            "ref-2",
            Set.of(DivergentField.BIRTH_DATE),
            Instant.now(),
            null));

    List<AssuranceCheck> todos = repository.findAll(subjectId, "default");

    assertThat(todos).hasSize(2);
    assertThat(todos)
        .extracting(AssuranceCheck::providerReference)
        .containsExactlyInAnyOrder("ref-1", "ref-2");
  }

  // --- V038: PIN e fila de pendências do poller -------------------------------------------------

  /** Round-trip de {@code pin}/{@code pin_expires_at} nunca tinha sido exercitado contra Postgres. */
  @Test
  void roundTripPreservaPinEExpiracao() {
    UUID subjectId = novoSubject("10122222166");
    Instant expiresAt = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MICROS);
    AssuranceCheck pendente =
        AssuranceCheck.pendingWithPin(
            UUID.randomUUID(),
            subjectId,
            "default",
            "datavalid-serpro",
            "hash-pin",
            Instant.now(),
            "654321",
            expiresAt);

    repository.save(pendente);

    AssuranceCheck lido =
        repository.findLatest(subjectId, "default", AssuranceKind.BIOMETRIC).orElseThrow();

    assertThat(lido.outcome()).isEqualTo(AssuranceOutcome.PENDING);
    assertThat(lido.pin()).isEqualTo("654321");
    assertThat(lido.pinExpiresAt()).isEqualTo(expiresAt);
  }

  /**
   * {@code claimPendingBiometric} é a query nova do poller ({@code FOR UPDATE SKIP LOCKED} +
   * lease, mesmo padrão de {@code OutboxRepository.claimPending}) — nunca tinha rodado contra
   * Postgres real. Reivindica um check {@code PENDING} e marca a posse ({@code claimed_at}); uma
   * segunda reivindicação imediata não pega a mesma linha, porque a lease ainda não venceu.
   */
  @Test
  void claimPendingBiometricReivindicaEBloqueiaAtéALeaseVencer() {
    UUID subjectId = novoSubject("10133333235");
    repository.save(
        AssuranceCheck.pendingWithPin(
            UUID.randomUUID(),
            subjectId,
            "default",
            "datavalid-serpro",
            "hash",
            Instant.now(),
            "111222",
            Instant.now().plusSeconds(300)));

    List<AssuranceCheck> primeira = repository.claimPendingBiometric(10, java.time.Duration.ofMinutes(1));
    List<AssuranceCheck> segunda = repository.claimPendingBiometric(10, java.time.Duration.ofMinutes(1));

    assertThat(primeira).hasSize(1);
    assertThat(primeira.get(0).pin()).isEqualTo("111222");
    assertThat(segunda).isEmpty();
  }

  /** Checks já resolvidos (PASS/FAIL/...) não entram na fila do poller — só PENDING. */
  @Test
  void claimPendingBiometricIgnoraChecksJaResolvidos() {
    UUID subjectId = novoSubject("10144444304");
    repository.save(
        checagem(
            subjectId, "default", AssuranceKind.BIOMETRIC, 90, "ref-1", Set.of(), Instant.now(), null));

    List<AssuranceCheck> reivindicados =
        repository.claimPendingBiometric(10, java.time.Duration.ofMinutes(1));

    assertThat(reivindicados).isEmpty();
  }
}
