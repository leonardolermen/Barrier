package com.barrier.riskengine.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.EvaluatedRule;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.domain.model.RuleOutcome;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.registry.service.RiskRuleRegistryService;
import com.barrier.riskengine.risk.repository.interfaces.RiskScoreRepository;
import com.barrier.riskengine.risk.rule.CorporateStructureRiskRule;
import com.barrier.riskengine.risk.rule.IdentityRiskRule;
import com.barrier.riskengine.risk.rule.PepRiskRule;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.risk.rule.SanctionRiskRule;
import com.barrier.riskengine.screening.domain.enums.MatchBasis;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.ScreenedParty;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskScoringServiceTest {

  @Mock RiskScoreRepository repository;
  @Mock RiskRuleRegistryService registryService;

  private RiskScoringService service;
  private List<RiskRule> configuredRules;

  @BeforeEach
  void setUp() {
    when(repository.save(any(RiskScore.class))).thenAnswer(inv -> inv.getArgument(0));
    when(registryService.isActive(anyString())).thenReturn(true);
    configuredRules =
        List.of(
            new IdentityRiskRule(),
            new SanctionRiskRule(),
            new PepRiskRule(),
            new CorporateStructureRiskRule());
    service = new RiskScoringService(configuredRules, repository, registryService);
  }

  private RiskContext context(IdentityStatus identity, ScreeningHit... hits) {
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", identity, "stub", "d"),
        ScreeningResult.of("aid", List.of(hits)),
        null,
        null);
  }

  /** PJ com sócio estrangeiro: dispara CORPORATE_STRUCTURE (regra de apetite, desligável). */
  private RiskContext contextComSocioEstrangeiro() {
    CompanyProfile company =
        new CompanyProfile(
            LocalDate.of(2015, 1, 1),
            "6201500",
            "Desenvolvimento de software",
            List.of(new CompanyProfile.Partner("JOHN DOE", false, true, "Sócio-Administrador")));
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "brasilapi", "d"),
        ScreeningResult.of("aid", List.of()),
        company,
        null);
  }

  @Test
  void verificadoSemApontamentoAprovaComScoreZero() {
    RiskDecision d = service.score(context(IdentityStatus.VERIFIED));

    assertThat(d.totalScore()).isZero();
    assertThat(d.level()).isEqualTo(RiskLevel.LOW);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.APPROVE);
    assertThat(d.results()).isEmpty();
    assertThat(d.engineVersion()).isEqualTo(RiskScoringService.ENGINE_VERSION);
  }

  @Test
  void sancaoPorDocumentoBloqueiaComScoreMaximo() {
    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(MatchType.SANCTION, MatchBasis.DOCUMENT, null, "OFAC", "X", "sdn")));

    assertThat(d.totalScore()).isEqualTo(1000);
    assertThat(d.level()).isEqualTo(RiskLevel.CRITICAL);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REJECT);
  }

  /**
   * Regressão: match fuzzy de nome contra a SDN reprovava automaticamente. Um homônimo de
   * sancionado tem de ir para análise humana, não ser recusado sem recurso.
   */
  @Test
  void sancaoApenasPorNomeVaiParaRevisaoNaoReprova() {
    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(MatchType.SANCTION, MatchBasis.NAME, null, "OFAC", "X", "sdn")));

    assertThat(d.totalScore()).isEqualTo(500);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(d.results().getFirst().ruleCode()).isEqualTo("SANCTION_NAME_MATCH");
  }

  /** Documento prevalece: já há identificação inequívoca, o indício por nome não a enfraquece. */
  @Test
  void sancaoPorDocumentoPrevaleceSobreMatchPorNome() {
    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(MatchType.SANCTION, MatchBasis.NAME, null, "OFAC", "X", "aka"),
                new ScreeningHit(MatchType.SANCTION, MatchBasis.DOCUMENT, null, "OFAC", "X", "sdn")));

    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REJECT);
  }

  @Test
  void pepForcaRevisao() {
    RiskDecision d =
        service.score(
            context(IdentityStatus.VERIFIED, new ScreeningHit(MatchType.PEP, MatchBasis.NAME, null, "base", "X", "cargo")));

    assertThat(d.totalScore()).isEqualTo(300);
    assertThat(d.level()).isEqualTo(RiskLevel.MEDIUM);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
  }

  @Test
  void identidadeNaoEncontradaBloqueia() {
    RiskDecision d = service.score(context(IdentityStatus.NOT_FOUND));

    assertThat(d.totalScore()).isEqualTo(900);
    assertThat(d.level()).isEqualTo(RiskLevel.CRITICAL);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REJECT);
  }

  /**
   * Regressão do achado que apareceu ao exercitar a API real: {@code PEP} (+300) e {@code
   * SANCTION_NAME_MATCH} (+500) pedem, cada um, revisão humana. Somados dão 800, cruzam o limiar de
   * 799 por um ponto e caíam na banda CRITICAL, que reprovava automaticamente — duas exigências de
   * julgamento humano produzindo uma recusa sem humano nenhum.
   *
   * <p>O nível continua sendo reportado como CRITICAL: o risco é alto mesmo. O que muda é o que se
   * faz com ele.
   */
  @Test
  void duasRegrasQuePedemRevisaoNaoSomamEmReprovacaoAutomatica() {
    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(MatchType.PEP, MatchBasis.NAME, null, "CGU", "X", "cargo"),
                new ScreeningHit(MatchType.SANCTION, MatchBasis.NAME, null, "OFAC", "X", "sdn")));

    assertThat(d.totalScore()).isEqualTo(800);
    assertThat(d.level()).isEqualTo(RiskLevel.CRITICAL);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
  }

  /**
   * Empresa limpa com sócio na lista: o apontamento existe e escala, mas <b>não</b> reprova a PJ
   * automaticamente — a entidade sancionada é o sócio, não a empresa. Vale mesmo quando o match do
   * sócio é por documento, que é o cenário que um provedor de KYB traria.
   */
  @Test
  void sancaoDeSocioEscalaMasNaoReprovaAEmpresa() {
    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(
                    MatchType.SANCTION,
                    MatchBasis.DOCUMENT,
                    ScreenedParty.socio("JOAO DA SILVA"),
                    "OFAC",
                    "SILVA, JOAO",
                    "sdn")));

    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(d.results().getFirst().ruleCode()).isEqualTo("SANCTION_NAME_MATCH");
    assertThat(d.results().getFirst().evidences().getFirst()).contains("sócio JOAO DA SILVA");
  }

  /** O titular sancionado por documento segue reprovando, com ou sem apontamento de sócio junto. */
  @Test
  void sancaoDoTitularPorDocumentoContinuaReprovando() {
    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(
                    MatchType.SANCTION,
                    MatchBasis.DOCUMENT,
                    ScreenedParty.socio("JOAO DA SILVA"),
                    "OFAC",
                    "SILVA, JOAO",
                    "sdn"),
                new ScreeningHit(MatchType.SANCTION, MatchBasis.DOCUMENT, null, "OFAC", "X", "sdn")));

    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REJECT);
  }

  /**
   * A banda sozinha não reprova, nem no topo da escala: acúmulo de score sem nenhuma regra pedindo
   * recusa escala até a revisão humana e para ali.
   */
  @Test
  void bandaCriticalSemRegraQuePecaRecusaVaiParaRevisao() {
    RiskDecision d = comRegraExtra(pontuaSemRecomendar(950)).score(context(IdentityStatus.VERIFIED));

    assertThat(d.level()).isEqualTo(RiskLevel.CRITICAL);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
  }

  /** O que a banda continua fazendo — e deve fazer: agravar APPROVE para REVIEW por acúmulo. */
  @Test
  void bandaAltaAindaAgravaAprovacaoParaRevisao() {
    RiskDecision d = comRegraExtra(pontuaSemRecomendar(600)).score(context(IdentityStatus.VERIFIED));

    assertThat(d.level()).isEqualTo(RiskLevel.HIGH);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
  }

  private RiskScoringService comRegraExtra(RiskRule extra) {
    List<RiskRule> rules = new java.util.ArrayList<>(configuredRules);
    rules.add(extra);
    return new RiskScoringService(rules, repository, registryService);
  }

  /** Regra de apetite: pontua alto e não opina sobre o desfecho — quem decide é a banda. */
  private static RiskRule pontuaSemRecomendar(int score) {
    return new RiskRule() {
      @Override
      public com.barrier.riskengine.risk.domain.model.RiskResult evaluate(RiskContext context) {
        return new com.barrier.riskengine.risk.domain.model.RiskResult(
            "ACUMULO",
            score,
            com.barrier.riskengine.risk.domain.enums.Severity.MEDIUM,
            "fatores de atenção acumulados",
            List.of("teste"),
            null);
      }

      @Override
      public String code() {
        return "ACUMULO";
      }
    };
  }

  /**
   * O núcleo da auditabilidade: provar que um controle <b>rodou e passou</b>. Guardando só as
   * regras que dispararam, "a regra de sanção não aparece na trilha" tinha três leituras
   * indistinguíveis — rodou e estava limpo, estava desligada, ou a lista estava vazia.
   */
  @Test
  void trilhaRegistraTodasAsRegrasAvaliadasNaoSoAsQueDispararam() {
    RiskDecision d = service.score(context(IdentityStatus.VERIFIED));

    assertThat(d.results()).isEmpty();
    assertThat(d.evaluated())
        .extracting(EvaluatedRule::ruleCode)
        .containsExactlyInAnyOrder("IDENTITY", "SANCTION", "PEP", "CORPORATE_STRUCTURE");
    assertThat(d.evaluated())
        .allSatisfy(rule -> assertThat(rule.outcome()).isEqualTo(RuleOutcome.NOT_TRIGGERED));
  }

  /** Regra suprimida pelo registry fica registrada como tal — não some da trilha. */
  @Test
  void regraSuprimidaPeloRegistryApareceNaTrilhaComoSuprimida() {
    when(registryService.isActive("CORPORATE_STRUCTURE")).thenReturn(false);

    RiskDecision d = service.score(contextComSocioEstrangeiro());

    assertThat(d.evaluated())
        .filteredOn(rule -> rule.ruleCode().equals("CORPORATE_STRUCTURE"))
        .singleElement()
        .satisfies(
            rule -> {
              assertThat(rule.outcome()).isEqualTo(RuleOutcome.SUPPRESSED);
              assertThat(rule.result()).isNull();
            });
  }

  /** A regra que disparou aparece com o resultado que produziu, e as outras seguem na trilha. */
  @Test
  void regraQueDisparouEAsQuePassaramConvivemNaTrilha() {
    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(MatchType.SANCTION, MatchBasis.NAME, null, "OFAC", "X", "sdn")));

    assertThat(d.evaluated())
        .filteredOn(rule -> rule.outcome() == RuleOutcome.TRIGGERED)
        .singleElement()
        .satisfies(rule -> assertThat(rule.ruleCode()).isEqualTo("SANCTION"));
    assertThat(d.evaluated())
        .filteredOn(rule -> rule.outcome() == RuleOutcome.NOT_TRIGGERED)
        .extracting(EvaluatedRule::ruleCode)
        .contains("IDENTITY", "PEP");
  }

  /**
   * O kill switch continua valendo para regras de <b>apetite de risco</b> — este é o caso de uso
   * legítimo do registry: desarmar uma regra que passou a gerar falso positivo em massa, sem
   * esperar deploy.
   */
  @Test
  void regraDeApetiteDesabilitadaNoRegistryNaoContribuiParaOScore() {
    when(registryService.isActive("CORPORATE_STRUCTURE")).thenReturn(false);

    RiskDecision d = service.score(contextComSocioEstrangeiro());

    assertThat(d.totalScore()).isZero();
    assertThat(d.level()).isEqualTo(RiskLevel.LOW);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.APPROVE);
    assertThat(d.results()).isEmpty();
  }

  /**
   * Regressão do achado mais explorável da auditoria: um {@code PUT /v1/risk-rules/SANCTION
   * {enabled:false}} zerava o screening de sanções para todos os tenants. Mesmo que o registry
   * responda "inativa" (linha escrita direto no banco, ou por uma versão anterior da API), o motor
   * tem de executar a regra.
   */
  @Test
  void regraRegulatoriaDesabilitadaNoRegistryAindaAssimBloqueia() {
    when(registryService.isActive("SANCTION")).thenReturn(false);

    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(MatchType.SANCTION, MatchBasis.DOCUMENT, null, "OFAC", "X", "sdn")));

    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REJECT);
    assertThat(d.level()).isEqualTo(RiskLevel.CRITICAL);
  }

  /**
   * Regressão do fail-open mais grave da auditoria: com bureau indisponível a identidade não foi
   * confirmada por nenhum provider, então o motor não pode aprovar — mesmo que o score (150) caia
   * na banda LOW. A recomendação da regra tem que sobrepor a banda.
   */
  @Test
  void bureauIndisponivelNaoAprova() {
    RiskDecision d = service.score(context(IdentityStatus.UNAVAILABLE));

    assertThat(d.totalScore()).isEqualTo(150);
    assertThat(d.level()).isEqualTo(RiskLevel.LOW);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(d.results()).hasSize(1);
    assertThat(d.results().getFirst().ruleCode()).isEqualTo("IDENTITY_UNAVAILABLE");
  }
}
