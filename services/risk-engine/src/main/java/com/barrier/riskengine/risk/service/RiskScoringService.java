package com.barrier.riskengine.risk.service;

import com.barrier.riskengine.risk.domain.model.EvaluatedRule;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.domain.model.RuleOutcome;
import com.barrier.riskengine.risk.domain.model.ScoreAggregation;
import com.barrier.riskengine.risk.registry.domain.RegulatoryRiskRules;
import com.barrier.riskengine.risk.registry.service.RiskRuleRegistryService;
import com.barrier.riskengine.risk.repository.interfaces.RiskScoreRepository;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Motor de risco: executa as regras ativas (Strategy), soma os scores em bandas de nível
 * (0–1000) e toma a recomendação mais severa entre a banda e os overrides das regras.
 * Persiste o score com os fatores e a versão do motor (explicabilidade + auditoria).
 *
 * <p>Bandas: ≤199 BAIXO · ≤499 MEDIO · ≤799 ALTO · &gt;799 CRITICO. A banda agrava a decisão por
 * acúmulo <b>até a revisão humana</b>; a reprovação automática exige uma regra que a peça
 * nominalmente (ver {@link ScoreAggregation#bandRecommendation}). Regras podem forçar overrides acima da banda
 * (sanção por documento → REJECT; PEP → REVIEW).
 *
 * <p>Uma regra só é executada se {@link RiskRuleRegistryService#isActive(String)} disser que
 * está habilitada e dentro da vigência — ajuste operacional sem deploy, sobre o
 * {@link RiskRule#code()} (família), não sobre o {@code ruleCode} granular do resultado.
 *
 * <p>{@code ENGINE_VERSION} deve ser incrementado a cada mudança de regra ou peso, preservando
 * o histórico das decisões tomadas por versões anteriores.
 */
@Service
public class RiskScoringService {

  private static final Logger log = LoggerFactory.getLogger(RiskScoringService.class);

  // 1.8.0: CorporateStructureCoverageRiskRule (regulatória) força REVIEW quando o bureau confirma
  // a PJ mas o CompanyProfile chega sem QSA (basic_data da BigBoost não traz sócios) — antes,
  // sócio sancionado numa PJ atendida por esse bureau não gerava apontamento nenhum e a avaliação
  // concluía APROVADO em silêncio.
  // Também: ScreeningCoverageRiskRule passou a exigir cobertura de ADVERSE_MEDIA, mas só quando
  // existe NegativeMediaProvider autoritativo (contratado) — não incondicionalmente como
  // SANCTION/PEP. ADVERSE_MEDIA nunca é populada em WatchlistImportStatus (mídia negativa é
  // consultada ao vivo por avaliação, não importada como WatchlistSource), então exigi-la sempre
  // faz a regra pontuar 100% das avaliações sem provedor contratado — pior que o fail-open que
  // existia para fechar, e recria o ruído que motivou o SOLICITAR_DOCUMENTO (ver
  // plano-remediacao-auditoria.md). Sem provedor autoritativo, a regra não pontua por isso; com
  // um contratado, entra na exigência como as outras duas.
  //
  // 1.7.0: o AssessmentProcessor passou a montar o AssuranceSummary (antes o RiskContext nascia
  // sem ele) e a IdentityAssuranceRiskRule, que já existia e nunca disparava, passou a rodar de
  // verdade em produção. Nenhuma regra nem peso mudou, mas uma regra que existia e não pontuava
  // agora pontua para o mesmo insumo — não subir mentiria na auditoria.
  //
  // 1.5.0: o screening passou a consultar sócios do QSA e representante legal, não só o titular —
  // uma PJ limpa com sócio em lista deixa de sair aprovada automaticamente. Apontamento de parte
  // relacionada escala para revisão, mas nunca reprova a PJ sozinho (a entidade sancionada é o
  // sócio, não a empresa).
  //
  // 1.4.0: a banda de score deixou de poder reprovar sozinha — agrava até REVIEW, e REJECT exige
  // uma regra que o peça nominalmente. Antes, duas regras que pediam revisão (PEP 300 +
  // SANCTION_NAME_MATCH 500) somavam 800, cruzavam o limiar de 799 e viravam recusa automática.
  //
  // 1.3.0: o match por nome do screening passou a comparar token a token e nos dois sentidos
  // (antes: Jaro-Winkler sobre a string inteira, limiar 0.95, que não casava nome em ordem
  // invertida — o formato em que as listas de sanção publicam). Nenhuma regra ou peso mudou, mas
  // o insumo mudou: a partir daqui a mesma pessoa pode gerar apontamento onde antes não gerava, e
  // por isso a versão sobe. E o stub de CPF deixou de ser fallback de bureau real indisponível
  // (indisponibilidade agora vira IDENTITY_UNAVAILABLE → REVIEW, não identidade verificada).
  //
  // 1.2.0: IDENTITY_UNAVAILABLE passou a forçar REVIEW (era fail-open para APPROVE) e SANCTION
  // separou match por documento (REJECT) de match por nome (REVIEW).
  static final String ENGINE_VERSION = "barrier-risk-rules/1.8.0";

  private final List<RiskRule> rules;
  private final RiskScoreRepository repository;
  private final RiskRuleRegistryService registryService;

  public RiskScoringService(
      List<RiskRule> rules,
      RiskScoreRepository repository,
      RiskRuleRegistryService registryService) {
    this.rules = rules;
    this.repository = repository;
    this.registryService = registryService;
  }

  /**
   * Avalia e <b>persiste</b> a decisão. É o caminho do pipeline.
   *
   * <p>Separado de {@link #evaluate} de propósito: o replay de decisão precisa reexecutar as regras
   * sem gravar nada, e a garantia de que ele não polui a trilha vem de <b>não ter como</b> — não de
   * lembrar de não chamar o save. Pelo mesmo caminho ele também não dispara {@code
   * AssessmentCompletedListener}, não atualiza {@code subject_risk_state} e não escreve na outbox.
   */
  public RiskDecision score(RiskContext context) {
    RiskDecision decision = evaluate(context);
    repository.save(RiskScore.from(context, decision));
    return decision;
  }

  /**
   * Executa as regras ativas e agrega, <b>sem efeito colateral algum</b>. Nada é persistido, nada é
   * publicado.
   */
  public RiskDecision evaluate(RiskContext context) {
    // Toda regra do motor entra na trilha, com o que aconteceu com ela. Guardar só as que
    // dispararam tornava indistinguíveis "rodou e passou", "estava desligada" e "a lista estava
    // vazia" — três leituras da mesma ausência, e só uma é aceitável.
    List<EvaluatedRule> evaluated =
        rules.stream()
            .map(
                rule -> {
                  if (!activeOrLogSuppressed(rule)) {
                    return EvaluatedRule.suppressed(rule.code());
                  }
                  RiskResult result = rule.evaluate(context);
                  // Parâmetros efetivos junto do desfecho, para toda regra que rodou — inclusive
                  // as que passaram. É o que permite responder, meses depois, por que a regra não
                  // pegou este cliente: o valor de hoje em `tenant_risk_config` pode não ser o de
                  // então, e sem isso a pergunta não tem resposta.
                  java.util.Map<String, String> parameters = rule.effectiveParameters(context);
                  return result.triggered()
                      ? EvaluatedRule.triggered(rule.code(), result, parameters)
                      : EvaluatedRule.passed(rule.code(), result, parameters);
                })
            .toList();

    List<RiskResult> triggered =
        evaluated.stream()
            .filter(rule -> rule.outcome() == RuleOutcome.TRIGGERED)
            .map(EvaluatedRule::result)
            .toList();

    ScoreAggregation agregado = ScoreAggregation.of(triggered);
    return new RiskDecision(
        agregado.level(),
        agregado.recommendation(),
        agregado.totalScore(),
        triggered,
        evaluated,
        ENGINE_VERSION);
  }

  /**
   * Regra regulatória ({@link RegulatoryRiskRules}) roda sempre — o registry não é consultado. Se
   * algum caminho tiver gravado essa regra como inativa, é incidente de segurança, não configuração
   * legítima: loga em WARN e executa a regra mesmo assim.
   */
  private boolean activeOrLogSuppressed(RiskRule rule) {
    if (RegulatoryRiskRules.isRegulatory(rule.code())) {
      if (!registryService.isActive(rule.code())) {
        log.warn(
            "Regra regulatória {} consta como inativa no registry — ignorando e executando mesmo "
                + "assim. Investigar quem gravou esse estado.",
            rule.code());
      }
      return true;
    }
    boolean active = registryService.isActive(rule.code());
    if (!active) {
      log.debug("Regra {} suprimida pelo registry (desabilitada ou fora de vigência)", rule.code());
    }
    return active;
  }
}
