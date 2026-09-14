package com.barrier.riskengine.riskpolicy.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.PolicyStatus;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicy;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import com.barrier.riskengine.riskpolicy.repository.interfaces.RiskPolicyRepository;
import java.time.Instant;
import java.time.Period;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Prova o repositório de {@link RiskPolicy} contra Postgres real: o índice único parcial de "uma
 * ACTIVE por (tenant, domínio)" e o round-trip da árvore de predicados por {@code rules_json} nunca
 * tinham sido exercitados contra um banco de verdade.
 *
 * <p>{@code so_uma_versao_ativa_por_tenant_e_dominio} é o teste que sustenta a garantia sob 5
 * réplicas: a trava tem que estar no banco, não só no caminho de serviço que arquiva antes de
 * ativar — duas réplicas podem tentar a ativação ao mesmo tempo sem passar pela mesma instância de
 * {@code RiskPolicyService}.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class RiskPolicyRepositoryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired RiskPolicyRepository repository;
  @Autowired JdbcTemplate jdbc;

  private void garanteTenant(String tenantId) {
    jdbc.update(
        "INSERT INTO tenants (id, name, active) VALUES (?, ?, true) ON CONFLICT (id) DO NOTHING",
        tenantId,
        tenantId);
  }

  private PolicyRule regraSimples(String codigo) {
    Condition when =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            Operator.WITHIN_LAST,
            Literal.duration(Period.ofMonths(6)));
    return new PolicyRule(
        codigo, "empresa nova", when, 100, Severity.MEDIUM, RiskRecommendation.REVIEW);
  }

  private RiskPolicy draft(String tenantId, int version, List<PolicyRule> rules) {
    return new RiskPolicy(
        UUID.randomUUID(),
        tenantId,
        PolicyDomain.ONBOARDING,
        version,
        PolicyStatus.DRAFT,
        FieldCatalog.VERSION,
        rules,
        "ana@parceiro",
        Instant.now(),
        null,
        null,
        null);
  }

  @Test
  void guarda_e_le_a_versao_ativa() {
    String tenantId = "t-guarda-le";
    garanteTenant(tenantId);
    RiskPolicy criada =
        repository.create(
            draft(tenantId, repository.nextVersion(tenantId), List.of(regraSimples("CUSTOM_A"))));

    repository.activate(criada.id(), "supervisor@parceiro", Instant.now());

    Optional<RiskPolicy> ativa = repository.findActive(tenantId, PolicyDomain.ONBOARDING);
    assertThat(ativa).isPresent();
    assertThat(ativa.get().id()).isEqualTo(criada.id());
    assertThat(ativa.get().status()).isEqualTo(PolicyStatus.ACTIVE);
    assertThat(ativa.get().activatedBy()).isEqualTo("supervisor@parceiro");
    assertThat(ativa.get().activatedAt()).isNotNull();
  }

  @Test
  void so_uma_versao_ativa_por_tenant_e_dominio() {
    String tenantId = "t-uma-ativa";
    garanteTenant(tenantId);
    RiskPolicy v1 =
        repository.create(
            draft(tenantId, repository.nextVersion(tenantId), List.of(regraSimples("CUSTOM_A"))));
    RiskPolicy v2 =
        repository.create(
            draft(tenantId, repository.nextVersion(tenantId), List.of(regraSimples("CUSTOM_B"))));

    repository.activate(v1.id(), "supervisor@parceiro", Instant.now());

    // Ativar a v2 SEM arquivar a v1 tem que violar o índice único parcial no banco -- é a trava
    // que vale sob corrida entre réplicas, não só o caminho feliz do serviço.
    assertThatThrownBy(() -> repository.activate(v2.id(), "supervisor@parceiro", Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void versao_e_monotonica_por_tenant() {
    String tenantId = "t-monotonica";
    garanteTenant(tenantId);

    assertThat(repository.nextVersion(tenantId)).isEqualTo(1);
    repository.create(
        draft(tenantId, repository.nextVersion(tenantId), List.of(regraSimples("CUSTOM_A"))));

    assertThat(repository.nextVersion(tenantId)).isEqualTo(2);
  }

  @Test
  void arvore_sobrevive_a_ida_e_volta_do_jsonb() {
    String tenantId = "t-arvore";
    garanteTenant(tenantId);

    Condition socioEstrangeiro =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.partners[].foreign").orElseThrow(),
            Operator.EQ,
            Literal.bool(true));
    Condition algumSocioEstrangeiro =
        new Condition.AnyOf(
            FieldCatalog.V1.find("company.partners").orElseThrow(), socioEstrangeiro);
    Condition identidadeNaoBate =
        new Condition.Not(
            new Condition.Comparison(
                FieldCatalog.V1.find("identity.status").orElseThrow(),
                Operator.EQ,
                Literal.text("MATCH")));
    Condition aninhada = new Condition.And(List.of(algumSocioEstrangeiro, identidadeNaoBate));
    PolicyRule regra =
        new PolicyRule(
            "CUSTOM_ANINHADA",
            "socio estrangeiro e identidade",
            aninhada,
            200,
            Severity.HIGH,
            RiskRecommendation.REJECT);

    RiskPolicy criada =
        repository.create(draft(tenantId, repository.nextVersion(tenantId), List.of(regra)));

    RiskPolicy lida = repository.findByTenantAndVersion(tenantId, criada.version()).orElseThrow();

    assertThat(lida.rules()).hasSize(1);
    assertThat(lida.rules().get(0).code()).isEqualTo("CUSTOM_ANINHADA");
    assertThat(lida.rules().get(0).when()).isEqualTo(aninhada);
  }

  @Test
  void tenant_sem_politica_devolve_vazio() {
    String tenantId = "t-sem-politica";
    garanteTenant(tenantId);

    assertThat(repository.findActive(tenantId, PolicyDomain.ONBOARDING)).isEmpty();
  }
}
