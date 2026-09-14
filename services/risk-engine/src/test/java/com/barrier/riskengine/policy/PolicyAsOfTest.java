package com.barrier.riskengine.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.risk.registry.domain.RiskRuleCriticality;
import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import com.barrier.riskengine.risk.registry.repository.interfaces.RiskRuleRegistryRepository;
import com.barrier.riskengine.risk.registry.service.RiskRuleRegistryServiceImpl;
import com.barrier.riskengine.tenant.config.repository.TenantRiskConfigRepository;
import com.barrier.riskengine.tenant.config.service.TenantRiskConfigAdminService;
import com.barrier.riskengine.tenant.config.validation.TenantRiskConfigValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * As três proveniências, e a diferença entre elas é o produto inteiro deste item.
 *
 * <p>Confundir <b>"nunca foi alterada"</b> com <b>"foi alterada só depois"</b> significa afirmar o
 * estado de hoje como se fosse o de então — que é literalmente o erro que a V033 foi criada para
 * evitar. Se estes testes passarem por acidente, a leitura de política mente com confiança.
 */
@ExtendWith(MockitoExtension.class)
class PolicyAsOfTest {

  private static final Instant DECIDIDA_EM = Instant.parse("2026-03-01T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

  @Mock RiskRuleRegistryRepository registryRepository;
  @Mock TenantRiskConfigRepository configRepository;
  @Mock TenantRiskConfigValidator validator;

  private RiskRuleRegistryServiceImpl registry() {
    return new RiskRuleRegistryServiceImpl(registryRepository, CLOCK);
  }

  private TenantRiskConfigAdminService config() {
    return new TenantRiskConfigAdminService(configRepository, validator);
  }

  // ---------- registry ----------

  @Test
  void alteracao_em_vigor_no_instante_traz_o_estado_e_a_autoria() {
    RegistryPolicyState gravado =
        new RegistryPolicyState(
            "NEW_COMPANY",
            false,
            "ALERT",
            "Empresa recém-aberta",
            null,
            null,
            "compliance@barrier",
            Instant.parse("2026-02-20T14:00:00Z"),
            PolicyProvenance.FROM_HISTORY);
    when(registryRepository.historyAsOf("NEW_COMPANY", DECIDIDA_EM)).thenReturn(Optional.of(gravado));

    assertThat(registry().stateAsOf("NEW_COMPANY", DECIDIDA_EM))
        .hasValueSatisfying(
            state -> {
              assertThat(state.enabled()).isFalse();
              assertThat(state.changedBy()).isEqualTo("compliance@barrier");
              assertThat(state.provenance()).isEqualTo(PolicyProvenance.FROM_HISTORY);
            });
  }

  @Test
  void regra_nunca_alterada_vale_desde_a_semente() {
    when(registryRepository.historyAsOf("PEP", DECIDIDA_EM)).thenReturn(Optional.empty());
    when(registryRepository.hasAnyHistory("PEP")).thenReturn(false);
    when(registryRepository.findByRuleCode("PEP"))
        .thenReturn(
            Optional.of(
                new RiskRuleRegistryEntry(
                    "PEP", "PEP — EDD", RiskRuleCriticality.REVIEW, true, null, null, null)));

    assertThat(registry().stateAsOf("PEP", DECIDIDA_EM))
        .hasValueSatisfying(
            state -> {
              assertThat(state.enabled()).isTrue();
              assertThat(state.provenance()).isEqualTo(PolicyProvenance.UNCHANGED_SINCE_SEED);
              assertThat(state.changedBy()).as("semente não tem autor").isNull();
            });
  }

  @Test
  void regra_sem_linha_no_registry_estava_ativa_por_fail_open() {
    // O registry é kill switch, não allowlist. A V016 semeia seis famílias e o motor tem dezesseis,
    // então este é o caso COMUM — tratá-lo como "autoria não apurável" marcaria a maioria das
    // regras do dossiê como lacuna, e sinal que dispara sempre deixa de ser sinal.
    when(registryRepository.historyAsOf("DEBARMENT", DECIDIDA_EM)).thenReturn(Optional.empty());
    when(registryRepository.hasAnyHistory("DEBARMENT")).thenReturn(false);
    when(registryRepository.findByRuleCode("DEBARMENT")).thenReturn(Optional.empty());

    assertThat(registry().stateAsOf("DEBARMENT", DECIDIDA_EM))
        .hasValueSatisfying(
            state -> {
              assertThat(state.enabled()).as("sem linha no registry, a regra roda").isTrue();
              assertThat(state.provenance()).isEqualTo(PolicyProvenance.NOT_REGISTERED);
            });
  }

  @Test
  void alteracao_somente_posterior_nao_permite_afirmar_o_estado_de_entao() {
    // O caso que separa este item de um bug: a V033 grava o estado NOVO de cada mudança. O anterior
    // à primeira não existe em lugar nenhum, e devolver o de hoje seria inventar a resposta.
    when(registryRepository.historyAsOf("SENSITIVE_CNAE", DECIDIDA_EM)).thenReturn(Optional.empty());
    when(registryRepository.hasAnyHistory("SENSITIVE_CNAE")).thenReturn(true);

    assertThat(registry().stateAsOf("SENSITIVE_CNAE", DECIDIDA_EM)).isEmpty();
  }

  // ---------- override por tenant ----------

  @Test
  void override_em_vigor_traz_quem_o_definiu() {
    when(configRepository.authorshipAsOf("acme", "NEW_COMPANY", "months", DECIDIDA_EM))
        .thenReturn(
            Optional.of(
                new ParamAuthorship(
                    "months",
                    ParamAuthorship.ParamSource.TENANT_OVERRIDE,
                    "12",
                    "analista@barrier",
                    Instant.parse("2026-02-01T08:00:00Z"),
                    PolicyProvenance.FROM_HISTORY)));

    ParamAuthorship autoria =
        config().authorshipAsOf("acme", "NEW_COMPANY", "months", "12", DECIDIDA_EM);

    assertThat(autoria.source()).isEqualTo(ParamAuthorship.ParamSource.TENANT_OVERRIDE);
    assertThat(autoria.changedBy()).isEqualTo("analista@barrier");
  }

  @Test
  void sem_override_nenhum_o_valor_veio_do_default_global() {
    when(configRepository.authorshipAsOf("acme", "NEW_COMPANY", "months", DECIDIDA_EM))
        .thenReturn(Optional.empty());
    when(configRepository.hasAnyHistory("acme", "NEW_COMPANY", "months")).thenReturn(false);

    ParamAuthorship autoria =
        config().authorshipAsOf("acme", "NEW_COMPANY", "months", "6", DECIDIDA_EM);

    assertThat(autoria.source()).isEqualTo(ParamAuthorship.ParamSource.GLOBAL_DEFAULT);
    // Default é código; a autoria dele é o ENGINE_VERSION, não uma pessoa.
    assertThat(autoria.changedBy()).isNull();
    assertThat(autoria.value()).as("o valor vem da decisão, não é reconstruído aqui").isEqualTo("6");
  }

  @Test
  void override_criado_depois_da_decisao_nao_vira_default_global() {
    // Sem a checagem de hasAnyHistory, este caso responderia GLOBAL_DEFAULT — afirmando que não
    // havia override quando o que se sabe é apenas que não dá para saber.
    when(configRepository.authorshipAsOf("acme", "NEW_COMPANY", "months", DECIDIDA_EM))
        .thenReturn(Optional.empty());
    when(configRepository.hasAnyHistory("acme", "NEW_COMPANY", "months")).thenReturn(true);

    ParamAuthorship autoria =
        config().authorshipAsOf("acme", "NEW_COMPANY", "months", "6", DECIDIDA_EM);

    assertThat(autoria.source()).isEqualTo(ParamAuthorship.ParamSource.UNKNOWN);
    assertThat(autoria.provenance()).isEqualTo(PolicyProvenance.UNKNOWN_BEFORE_HISTORY);
  }
}
