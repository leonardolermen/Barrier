package com.barrier.riskengine.riskpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.policy.RegistryPolicyState;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.EvaluatedRule;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import com.barrier.riskengine.risk.registry.service.RiskRuleRegistryService;
import com.barrier.riskengine.risk.repository.interfaces.RiskScoreRepository;
import com.barrier.riskengine.risk.rule.CorporateStructureCoverageRiskRule;
import com.barrier.riskengine.risk.rule.CorporateStructureRiskRule;
import com.barrier.riskengine.risk.rule.DebarmentRiskRule;
import com.barrier.riskengine.risk.rule.IdentityRiskRule;
import com.barrier.riskengine.risk.rule.PepRiskRule;
import com.barrier.riskengine.risk.rule.SanctionRiskRule;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.CustomRuleSource;
import com.barrier.riskengine.risk.rule.interfaces.CustomRules;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.risk.service.RiskScoringService;
import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicy;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import com.barrier.riskengine.riskpolicy.repository.interfaces.RiskPolicyRepository;
import com.barrier.riskengine.riskpolicy.service.ConditionEvaluator;
import com.barrier.riskengine.riskpolicy.service.CustomPolicyRiskRule;
import com.barrier.riskengine.riskpolicy.service.PolicyCompilationException;
import com.barrier.riskengine.riskpolicy.service.PolicyCompiler;
import com.barrier.riskengine.riskpolicy.service.RiskPolicyService;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.domain.enums.MatchBasis;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A promessa do produto, verificada: para qualquer contexto e qualquer política compilável, a
 * decisão COM política nunca é mais fraca que a decisão SEM ela.
 *
 * <p>Não é teste de exemplo: é a propriedade que sustenta a decisão de abrir escrita de regra ao
 * parceiro. Se ela cair, o piso regulatório caiu junto.
 *
 * <p>Matriz curada (§11 do desenho): 5 contextos x 5 políticas, comparando sempre a mesma
 * avaliação com e sem a política entrando no motor. {@link #motorSemPolitica()} e {@link
 * #motorComPolitica(List)} montam o mesmo conjunto de regras de código nos dois lados — a única
 * diferença é a presença da política do parceiro.
 */
class InvarianteAditivoTest {

  private static final Instant AGORA = Instant.parse("2026-09-01T00:00:00Z");
  private static final String TENANT = "acme";
  private static final String ASSESSMENT_ID = "a1";

  private final FieldCatalog catalog = FieldCatalog.V1;
  private final PolicyCompiler compiler = new PolicyCompiler(catalog);
  private final ConditionEvaluator evaluator = new ConditionEvaluator();

  // ---------------------------------------------------------------------
  // Motor: mesmas regras de código nos dois lados da comparação
  // ---------------------------------------------------------------------

  private List<RiskRule> regrasDoMotor() {
    return List.of(
        new IdentityRiskRule(),
        new SanctionRiskRule(),
        new PepRiskRule(),
        new CorporateStructureRiskRule(),
        new CorporateStructureCoverageRiskRule(),
        new DebarmentRiskRule());
  }

  private RiskScoringService motorSemPolitica() {
    return new RiskScoringService(
        regrasDoMotor(), REPOSITORIO_NAO_USADO, SEMPRE_ATIVO, Optional.empty());
  }

  /** Compila a política pelo mesmo {@link PolicyCompiler} do caminho de produção antes de usá-la. */
  private RiskScoringService motorComPolitica(List<PolicyRule> regrasDaPolitica) {
    List<PolicyRule> compiladas = compiler.compile(regrasDaPolitica);
    List<RiskRule> customRules =
        compiladas.stream()
            .<RiskRule>map(regra -> new CustomPolicyRiskRule(regra, evaluator))
            .toList();
    CustomRuleSource source = ctx -> new CustomRules(1, customRules);
    return new RiskScoringService(
        regrasDoMotor(), REPOSITORIO_NAO_USADO, SEMPRE_ATIVO, Optional.of(source));
  }

  // ---------------------------------------------------------------------
  // Contextos (§11: PF limpa, PF com sanção, PJ sem QSA, PJ com sócio estrangeiro, identidade
  // indisponível)
  // ---------------------------------------------------------------------

  private RiskContext pfLimpa() {
    return new RiskContext(
        ASSESSMENT_ID,
        TENANT,
        IdentityCheck.create(ASSESSMENT_ID, IdentityStatus.VERIFIED, "stub", "ok"),
        ScreeningResult.of(ASSESSMENT_ID, List.of()),
        null,
        null,
        null,
        AGORA);
  }

  private RiskContext pfComSancao() {
    return new RiskContext(
        ASSESSMENT_ID,
        TENANT,
        IdentityCheck.create(ASSESSMENT_ID, IdentityStatus.VERIFIED, "stub", "ok"),
        ScreeningResult.of(
            ASSESSMENT_ID,
            List.of(
                new ScreeningHit(MatchType.SANCTION, MatchBasis.NAME, null, "OFAC", "X", "sdn"))),
        null,
        null,
        null,
        AGORA);
  }

  private RiskContext pjSemQsa() {
    CompanyProfile company =
        new CompanyProfile(LocalDate.of(2015, 1, 1), "6201-5/00", "desenvolvimento", List.of());
    return new RiskContext(
        ASSESSMENT_ID,
        TENANT,
        IdentityCheck.create(ASSESSMENT_ID, IdentityStatus.VERIFIED, "bigboost", "ok"),
        ScreeningResult.of(ASSESSMENT_ID, List.of()),
        company,
        null,
        null,
        AGORA);
  }

  private RiskContext pjComSocioEstrangeiro() {
    CompanyProfile company =
        new CompanyProfile(
            LocalDate.of(2015, 1, 1),
            "6201-5/00",
            "desenvolvimento",
            List.of(new CompanyProfile.Partner("JOHN DOE", false, true, "Sócio-Administrador")));
    return new RiskContext(
        ASSESSMENT_ID,
        TENANT,
        IdentityCheck.create(ASSESSMENT_ID, IdentityStatus.VERIFIED, "brasilapi", "ok"),
        ScreeningResult.of(ASSESSMENT_ID, List.of()),
        company,
        null,
        null,
        AGORA);
  }

  private RiskContext identidadeIndisponivel() {
    return new RiskContext(
        ASSESSMENT_ID,
        TENANT,
        IdentityCheck.create(ASSESSMENT_ID, IdentityStatus.UNAVAILABLE, "stub", "fora do ar"),
        ScreeningResult.of(ASSESSMENT_ID, List.of()),
        null,
        null,
        null,
        AGORA);
  }

  private List<ContextCase> contextos() {
    return List.of(
        new ContextCase("PF_LIMPA", pfLimpa()),
        new ContextCase("PF_COM_SANCAO", pfComSancao()),
        new ContextCase("PJ_SEM_QSA", pjSemQsa()),
        new ContextCase("PJ_COM_SOCIO_ESTRANGEIRO", pjComSocioEstrangeiro()),
        new ContextCase("IDENTIDADE_INDISPONIVEL", identidadeIndisponivel()));
  }

  // ---------------------------------------------------------------------
  // Políticas (§11: vazia, só pontua, força REVIEW, força REJECT, várias regras)
  // ---------------------------------------------------------------------

  private PolicyField campo(String id) {
    return catalog.find(id).orElseThrow();
  }

  private List<PolicyRule> politicaVazia() {
    return List.of();
  }

  private List<PolicyRule> politicaSoPontua() {
    Condition identidadeVerificada =
        new Condition.Comparison(campo("identity.status"), Operator.EQ, Literal.text("VERIFIED"));
    return List.of(
        new PolicyRule(
            "CUSTOM_SO_PONTUA", "só pontua", identidadeVerificada, 50, Severity.LOW, null));
  }

  private List<PolicyRule> politicaForcaReview() {
    Condition sempre =
        new Condition.Comparison(campo("identity.status"), Operator.IS_NOT_NULL, Literal.none());
    return List.of(
        new PolicyRule(
            "CUSTOM_FORCA_REVIEW",
            "força revisão",
            sempre,
            0,
            Severity.LOW,
            RiskRecommendation.REVIEW));
  }

  private List<PolicyRule> politicaForcaReject() {
    Condition socioEstrangeiro =
        new Condition.AnyOf(
            campo("company.partners"),
            new Condition.Comparison(
                campo("company.partners[].foreign"), Operator.EQ, Literal.bool(true)));
    return List.of(
        new PolicyRule(
            "CUSTOM_FORCA_REJECT",
            "sócio estrangeiro reprova",
            socioEstrangeiro,
            0,
            Severity.HIGH,
            RiskRecommendation.REJECT));
  }

  private List<PolicyRule> politicaComVariasRegras() {
    Condition identidadeVerificada =
        new Condition.Comparison(campo("identity.status"), Operator.EQ, Literal.text("VERIFIED"));
    Condition temSancao =
        new Condition.AnyOf(
            campo("screening.hits"),
            new Condition.Comparison(
                campo("screening.hits[].type"), Operator.EQ, Literal.text("SANCTION")));
    Condition socioEstrangeiro =
        new Condition.AnyOf(
            campo("company.partners"),
            new Condition.Comparison(
                campo("company.partners[].foreign"), Operator.EQ, Literal.bool(true)));
    return List.of(
        new PolicyRule(
            "CUSTOM_VARIAS_A",
            "identidade verificada",
            identidadeVerificada,
            30,
            Severity.LOW,
            null),
        new PolicyRule(
            "CUSTOM_VARIAS_B",
            "sanção por nome também revisa",
            temSancao,
            40,
            Severity.MEDIUM,
            RiskRecommendation.REVIEW),
        new PolicyRule(
            "CUSTOM_VARIAS_C",
            "sócio estrangeiro reprova",
            socioEstrangeiro,
            60,
            Severity.HIGH,
            RiskRecommendation.REJECT));
  }

  private List<PolicyCase> politicas() {
    return List.of(
        new PolicyCase("VAZIA", politicaVazia()),
        new PolicyCase("SO_PONTUA", politicaSoPontua()),
        new PolicyCase("FORCA_REVIEW", politicaForcaReview()),
        new PolicyCase("FORCA_REJECT", politicaForcaReject()),
        new PolicyCase("VARIAS_REGRAS", politicaComVariasRegras()));
  }

  // ---------------------------------------------------------------------
  // Os testes
  // ---------------------------------------------------------------------

  @Test
  void politica_nunca_enfraquece_a_decisao() {
    RiskScoringService semPolitica = motorSemPolitica();
    for (ContextCase contexto : contextos()) {
      RiskDecision sem = semPolitica.evaluate(contexto.context());
      for (PolicyCase politica : politicas()) {
        RiskDecision com = motorComPolitica(politica.rules()).evaluate(contexto.context());

        assertThat(com.totalScore())
            .as(
                "contexto=%s politica=%s: score com politica (%s) nao pode ser menor que sem (%s)",
                contexto.nome(), politica.nome(), com.totalScore(), sem.totalScore())
            .isGreaterThanOrEqualTo(sem.totalScore());

        assertThat(com.recommendation().strongest(sem.recommendation()))
            .as(
                "contexto=%s politica=%s: recomendacao com politica (%s) nao pode ser mais fraca"
                    + " que sem (%s)",
                contexto.nome(), politica.nome(), com.recommendation(), sem.recommendation())
            .isEqualTo(com.recommendation());
      }
    }
  }

  /**
   * Caso negativo deliberado, para provar que {@link #politica_nunca_enfraquece_a_decisao} e as
   * outras políticas da matriz não são vácuas por falta de score negativo: <b>nenhuma</b> das
   * políticas de {@link #politicas()} carrega score negativo (todas passam por {@link
   * PolicyCompiler#compile}, que recusa isso), então removê-lo do compilador não mudaria nada
   * naquele teste — a mutação só é pega por {@code
   * o_compilador_e_a_unica_porta_e_ela_recusa_score_negativo}, que prova que o compilador recusa
   * <b>compilar</b> a política, não que o motor <b>recusaria avaliá-la</b> se ela chegasse lá.
   *
   * <p>Aqui o {@link CustomPolicyRiskRule} é construído <b>à mão</b>, pulando {@link
   * PolicyCompiler} inteiro — o mesmo bypass que um segundo caminho de escrita em {@code
   * risk_policies} teria (ver {@code apenas_riskpolicyservice_escreve_em_risk_policy_repository}
   * no {@code LayeredArchitectureTest}). A recomendação não pode ser {@code null}: {@code
   * RiskResult.triggered()} exige {@code score > 0 || recommendation != null}, e um score
   * negativo com recomendação nula nem dispararia — não entraria na soma, e não provaria nada
   * sobre monotonicidade. Com recomendação não nula a regra dispara, o score negativo entra na
   * soma de {@code ScoreAggregation}, e o invariante <b>quebra</b> — é isso que a trava 1 do
   * compilador existe para impedir antes de a política se tornar avaliável.
   */
  @Test
  void sem_o_compilador_regra_de_score_negativo_quebra_o_invariante_bypass_deliberado() {
    Condition sempre =
        new Condition.Comparison(campo("identity.status"), Operator.IS_NOT_NULL, Literal.none());
    PolicyRule afrouxadoraNaoCompilada =
        new PolicyRule(
            "CUSTOM_BYPASS_COMPILADOR",
            "bypassa a trava 1 construindo o RiskRule direto",
            sempre,
            -500,
            Severity.LOW,
            RiskRecommendation.APPROVE);
    RiskRule regraCrua = new CustomPolicyRiskRule(afrouxadoraNaoCompilada, evaluator);
    CustomRuleSource source = ctx -> new CustomRules(1, List.of(regraCrua));
    RiskScoringService comBypass =
        new RiskScoringService(
            regrasDoMotor(), REPOSITORIO_NAO_USADO, SEMPRE_ATIVO, Optional.of(source));

    RiskContext contexto = pfLimpa();
    RiskDecision sem = motorSemPolitica().evaluate(contexto);
    RiskDecision com = comBypass.evaluate(contexto);

    assertThat(com.totalScore())
        .as(
            "sem o compilador, uma regra de score negativo reduz o total abaixo do que era sem"
                + " politica nenhuma -- e exatamente isso que a trava 1 existe para impedir")
        .isLessThan(sem.totalScore());
  }

  @Test
  void nenhuma_regra_custom_apaga_fator_do_motor() {
    RiskScoringService semPolitica = motorSemPolitica();
    for (ContextCase contexto : contextos()) {
      List<String> codigosSem =
          semPolitica.evaluate(contexto.context()).evaluated().stream()
              .map(EvaluatedRule::ruleCode)
              .toList();
      for (PolicyCase politica : politicas()) {
        List<String> codigosCom =
            motorComPolitica(politica.rules()).evaluate(contexto.context()).evaluated().stream()
                .map(EvaluatedRule::ruleCode)
                .toList();

        assertThat(codigosCom)
            .as(
                "contexto=%s politica=%s: todo ruleCode presente sem politica continua presente"
                    + " com politica",
                contexto.nome(), politica.nome())
            .containsAll(codigosSem);
      }
    }
  }

  /**
   * O compilador é o único portão para uma política virar regra ativa — não há caminho que grave
   * uma versão sem passar por {@link PolicyCompiler#compile}. O repositório desta chamada explode
   * se qualquer método dele for tocado: a política afrouxadora tem que morrer na compilação, antes
   * de qualquer tentativa de persistência.
   */
  @Test
  void o_compilador_e_a_unica_porta_e_ela_recusa_score_negativo() {
    RiskPolicyService service =
        new RiskPolicyService(REPOSITORIO_QUE_NAO_PODE_SER_TOCADO, compiler);
    Condition sempre =
        new Condition.Comparison(campo("identity.status"), Operator.IS_NOT_NULL, Literal.none());
    PolicyRule afrouxadora =
        new PolicyRule("CUSTOM_AFROUXA", "tenta afrouxar", sempre, -1, Severity.LOW, null);

    assertThatThrownBy(
            () ->
                service.createDraft(
                    TENANT, PolicyDomain.ONBOARDING, List.of(afrouxadora), "parceiro@acme"))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("score");
  }

  // ---------------------------------------------------------------------
  // Dobras: sem estado, só para satisfazer as dependências de RiskScoringService/RiskPolicyService
  // sem gravar nada e sem mascarar uma chamada indevida com um no-op silencioso.
  // ---------------------------------------------------------------------

  private record ContextCase(String nome, RiskContext context) {}

  private record PolicyCase(String nome, List<PolicyRule> rules) {}

  /** {@link RiskScoringService#evaluate} nunca persiste -- ver Javadoc do método. */
  private static final RiskScoreRepository REPOSITORIO_NAO_USADO =
      new RiskScoreRepository() {
        @Override
        public RiskScore save(RiskScore score) {
          throw new UnsupportedOperationException(
              "evaluate() nao persiste -- nao deveria chamar save");
        }

        @Override
        public List<RiskScore> findByAssessmentId(String assessmentId) {
          throw new UnsupportedOperationException();
        }
      };

  private static final RiskRuleRegistryService SEMPRE_ATIVO =
      new RiskRuleRegistryService() {
        @Override
        public boolean isActive(String ruleCode) {
          return true;
        }

        @Override
        public List<RiskRuleRegistryEntry> findAll() {
          return List.of();
        }

        @Override
        public Optional<RegistryPolicyState> stateAsOf(String ruleCode, Instant at) {
          return Optional.empty();
        }

        @Override
        public List<RegistryPolicyState> history(String ruleCode) {
          return List.of();
        }

        @Override
        public RiskRuleRegistryEntry upsert(
            String ruleCode,
            String description,
            String criticality,
            boolean enabled,
            Instant validFrom,
            Instant validUntil,
            String updatedBy) {
          throw new UnsupportedOperationException();
        }
      };

  private static final RiskPolicyRepository REPOSITORIO_QUE_NAO_PODE_SER_TOCADO =
      new RiskPolicyRepository() {
        @Override
        public RiskPolicy create(RiskPolicy policy) {
          throw new UnsupportedOperationException(
              "compilacao deveria ter rejeitado antes de qualquer chamada ao repositorio");
        }

        @Override
        public Optional<RiskPolicy> findActive(String tenantId, PolicyDomain domain) {
          throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RiskPolicy> findByTenantAndVersion(String tenantId, int version) {
          throw new UnsupportedOperationException();
        }

        @Override
        public List<RiskPolicy> listByTenant(String tenantId) {
          throw new UnsupportedOperationException();
        }

        @Override
        public int nextVersion(String tenantId) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void activate(UUID id, String activatedBy, Instant when) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void archive(UUID id, Instant when) {
          throw new UnsupportedOperationException();
        }
      };
}
