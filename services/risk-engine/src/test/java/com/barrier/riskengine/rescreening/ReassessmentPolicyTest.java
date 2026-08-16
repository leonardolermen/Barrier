package com.barrier.riskengine.rescreening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.rescreening.policy.domain.ReassessmentDecision;
import com.barrier.riskengine.rescreening.policy.domain.ReassessmentTrigger;
import com.barrier.riskengine.rescreening.policy.repository.interfaces.ReassessmentDecisionRepository;
import com.barrier.riskengine.rescreening.policy.service.ReassessmentPolicy;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.riskstate.domain.SubjectRiskState;
import com.barrier.riskengine.riskstate.service.SubjectRiskStateService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** A política do ADR-0019: gatilho, materialidade e intervalo mínimo por nível de risco. */
@ExtendWith(MockitoExtension.class)
class ReassessmentPolicyTest {

  private static final UUID SUBJECT = UUID.randomUUID();
  private static final String TENANT = "acme";

  @Mock SubjectRiskStateService riskState;
  @Mock ReassessmentDecisionRepository repository;

  private ReassessmentPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new ReassessmentPolicy(riskState, repository);
    lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  /**
   * O teste que existe para quebrar o build se alguém "otimizar" a política aplicando intervalo
   * mínimo ao rescreening por watchlist. Entrada nova em lista de sanção é fato adverso novo;
   * suprimi-la porque o cliente foi reavaliado ontem descumpre a Circular 3.978.
   */
  @Test
  void sancao_nova_reavalia_mesmo_com_decisao_de_ontem() {
    quandoCorrente(RiskLevel.LOW, Instant.now().minus(1, ChronoUnit.DAYS));

    ReassessmentDecision decisao =
        policy.decide(SUBJECT, TENANT, ReassessmentTrigger.WATCHLIST_DELTA, "OFAC@2026-08-15", true);

    assertThat(decisao.reassess()).isTrue();
    assertThat(decisao.reason()).isNull();
  }

  @Test
  void assurance_tambem_faz_bypass_do_intervalo() {
    quandoCorrente(RiskLevel.LOW, Instant.now().minus(1, ChronoUnit.HOURS));

    assertThat(
            policy
                .decide(SUBJECT, TENANT, ReassessmentTrigger.ASSURANCE, "DOCUMENT@abc", true)
                .reassess())
        .isTrue();
  }

  /** Rotina sobre cliente bom respeita os 3 anos: reavaliar custa consulta paga. */
  @Test
  void periodica_dentro_do_intervalo_do_nivel_nao_reavalia() {
    quandoCorrente(RiskLevel.LOW, Instant.now().minus(200, ChronoUnit.DAYS));

    ReassessmentDecision decisao =
        policy.decide(SUBJECT, TENANT, ReassessmentTrigger.PERIODIC, null, false);

    assertThat(decisao.reassess()).isFalse();
    assertThat(decisao.reason()).isEqualTo(ReassessmentDecision.INTERVALO_MINIMO);
    assertThat(decisao.riskLevel()).isEqualTo(RiskLevel.LOW);
  }

  /** Mesma idade de decisão, cliente ruim: 200 dias já passou dos 183 do CRITICAL. */
  @Test
  void periodica_sobre_cliente_critico_reavalia_com_a_mesma_idade_de_decisao() {
    quandoCorrente(RiskLevel.CRITICAL, Instant.now().minus(200, ChronoUnit.DAYS));

    assertThat(policy.decide(SUBJECT, TENANT, ReassessmentTrigger.PERIODIC, null, false).reassess())
        .isTrue();
  }

  @Test
  void patch_de_cadastro_sem_alteracao_material_nao_reavalia() {
    ReassessmentDecision decisao =
        policy.decide(SUBJECT, TENANT, ReassessmentTrigger.PROFILE_PATCH, "phone", false);

    assertThat(decisao.reassess()).isFalse();
    assertThat(decisao.reason()).isEqualTo(ReassessmentDecision.SEM_ALTERACAO_MATERIAL);
  }

  /** Desconhecido não é sinônimo de bom: sem projeção, o cliente cai no pior caso. */
  @Test
  void cliente_sem_projecao_reavalia() {
    when(riskState.find(SUBJECT, TENANT)).thenReturn(Optional.empty());

    ReassessmentDecision decisao =
        policy.decide(SUBJECT, TENANT, ReassessmentTrigger.PERIODIC, null, false);

    assertThat(decisao.reassess()).isTrue();
    assertThat(decisao.riskLevel()).isNull();
  }

  /** Toda passagem deixa linha — inclusive, e principalmente, a que decide não reavaliar. */
  @Test
  void decisao_negativa_tambem_e_gravada() {
    quandoCorrente(RiskLevel.LOW, Instant.now().minus(10, ChronoUnit.DAYS));

    policy.decide(SUBJECT, TENANT, ReassessmentTrigger.PERIODIC, null, false);

    org.mockito.Mockito.verify(repository).save(any());
  }

  /** Cada gatilho declara seus dois comportamentos; o bypass é propriedade do enum, não convenção. */
  @Test
  void matriz_de_gatilhos_do_adr_0019() {
    assertThat(ReassessmentTrigger.WATCHLIST_DELTA.respectsMinimumInterval()).isFalse();
    assertThat(ReassessmentTrigger.ASSURANCE.respectsMinimumInterval()).isFalse();
    assertThat(ReassessmentTrigger.MANUAL.respectsMinimumInterval()).isFalse();
    assertThat(ReassessmentTrigger.PERIODIC.respectsMinimumInterval()).isTrue();
    assertThat(ReassessmentTrigger.PROFILE_PATCH.requiresMaterialChange()).isTrue();
    assertThat(ReassessmentTrigger.REINTAKE.requiresMaterialChange()).isTrue();
    assertThat(ReassessmentTrigger.WATCHLIST_DELTA.requiresMaterialChange()).isFalse();
  }

  private void quandoCorrente(RiskLevel nivel, Instant avaliadaEm) {
    lenient()
        .when(riskState.find(SUBJECT, TENANT))
        .thenReturn(
            Optional.of(
                new SubjectRiskState(
                    SUBJECT,
                    TENANT,
                    nivel,
                    500,
                    AssessmentStatus.APROVADO,
                    UUID.randomUUID(),
                    "motor/1.0",
                    avaliadaEm,
                    avaliadaEm)));
  }
}
