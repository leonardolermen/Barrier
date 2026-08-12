package com.barrier.riskengine.risk.service;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.EvaluatedRule;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.domain.model.RuleOutcome;
import com.barrier.riskengine.risk.registry.domain.RegulatoryRiskRules;
import com.barrier.riskengine.risk.registry.service.RiskRuleRegistryService;
import com.barrier.riskengine.risk.repository.interfaces.RiskScoreRepository;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import java.util.List;
import java.util.Objects;

import com.barrier.riskengine.screening.domain.enums.MatchBasis;
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
 * nominalmente (ver {@link #bandRecommendation}). Regras podem forçar overrides acima da banda
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
  static final String ENGINE_VERSION = "barrier-risk-rules/1.6.0";
  private static final int MAX_SCORE = 1000;

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

  public RiskDecision score(RiskContext context) {
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

    int total = Math.min(MAX_SCORE, triggered.stream().mapToInt(RiskResult::score).sum());
    RiskLevel level = band(total);

    RiskRecommendation recommendation =
        triggered.stream()
            .map(RiskResult::recommendation)
            .filter(Objects::nonNull)
            .reduce(bandRecommendation(level), RiskRecommendation::strongest);

    RiskDecision decision =
        new RiskDecision(level, recommendation, total, triggered, evaluated, ENGINE_VERSION);
    repository.save(RiskScore.from(context, decision));
    return decision;
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

  private static RiskLevel band(int score) {
    if (score <= 199) {
      return RiskLevel.LOW;
    }
    if (score <= 499) {
      return RiskLevel.MEDIUM;
    }
    return score <= 799 ? RiskLevel.HIGH : RiskLevel.CRITICAL;
  }

  /**
   * O que a <b>banda</b> recomenda sozinha — e o teto disso é {@code REVIEW}, mesmo em CRITICAL.
   *
   * <p>A banda entra no {@code reduce} como valor inicial e disputa o {@code strongest} de igual
   * para igual com as regras, então antes ela podia agravar a decisão acima de tudo que qualquer
   * regra pediu. O efeito observado em produção-simulada: {@code PEP} (+300, pede REVIEW) somado a
   * {@code SANCTION_NAME_MATCH} (+500, pede REVIEW) dá 800, cruza o limiar de 799 por um ponto,
   * cai em CRITICAL e vira <b>reprovação automática</b>. Duas regras exigindo julgamento humano
   * produziam, somadas, uma recusa sem humano nenhum.
   *
   * <p>Isso anulava as duas decisões mais deliberadas do motor: {@link
   * MatchBasis} existe para que match por nome não reprove
   * homônimo sem revisão, e PEP não é impedimento de relacionamento — é gatilho de diligência
   * reforçada (Circular BCB 3.978). Pior: {@code SCREENING_COVERAGE} (+300) também pede REVIEW e
   * também é somável, então um cliente podia ser reprovado em definitivo em parte porque <i>a nossa
   * importação de watchlist</i> falhou.
   *
   * <p>Somar sinais para agravar {@code APPROVE → REVIEW} é o cerne da abordagem baseada em risco e
   * continua valendo. O que não vale é somar incertezas até virar certeza: ambiguidade acumulada
   * segue sendo ambiguidade, e reprovação é terminal — não tem recurso no sistema.
   *
   * <p>Nada de reprovação legítima se perde: as únicas regras que pedem REJECT hoje
   * ({@code IDENTITY_NOT_FOUND}, 900; {@code SANCTION_HIT} por documento, 1000) já ultrapassam 799
   * sozinhas. A banda CRITICAL nunca foi o que descobre uma recusa correta — só acrescentava as
   * incorretas. O nível de risco continua sendo reportado como CRITICAL: o que muda é o que se faz
   * com ele.
   */
  private static RiskRecommendation bandRecommendation(RiskLevel level) {
    return switch (level) {
      case LOW, MEDIUM -> RiskRecommendation.APPROVE;
      case HIGH, CRITICAL -> RiskRecommendation.REVIEW;
    };
  }
}
