package com.barrier.riskengine.replay.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.replay.domain.ArithmeticCheck;
import com.barrier.riskengine.replay.domain.DecisionNotReplayableException;
import com.barrier.riskengine.replay.domain.DecisionReplay;
import com.barrier.riskengine.replay.domain.GapKind;
import com.barrier.riskengine.replay.domain.RecordedDecision;
import com.barrier.riskengine.replay.domain.ReconstructionGap;
import com.barrier.riskengine.replay.domain.ReplayMode;
import com.barrier.riskengine.replay.domain.ReplayVerdict;
import com.barrier.riskengine.replay.domain.ReplayedDecision;
import com.barrier.riskengine.replay.domain.ReplayedRule;
import com.barrier.riskengine.replay.domain.RuleComparison;
import com.barrier.riskengine.risk.domain.model.EvaluatedRule;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.domain.model.RuleOutcome;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.risk.service.RiskScoreQueryService;
import com.barrier.riskengine.risk.service.RiskScoringService;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.service.ScreeningQueryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Replay de decisão: responde <i>o que foi decidido, com base em quê</i> e <i>o motor de hoje
 * decidiria o mesmo</i>.
 *
 * <p><b>O que este serviço deliberadamente não promete.</b> O plano de produto pedia "reproduz o
 * desfecho histórico bit a bit", e isso não é alcançável: regra é código, não dado, e o código que
 * produziu uma decisão de {@code barrier-risk-rules/1.4.0} não existe mais no binário. Reproduzir a
 * lógica da época exigiria regra versionada carregável em runtime — recusa já registrada duas vezes
 * no projeto, porque sacrificaria o {@code ENGINE_VERSION} e a própria trilha reproduzível. O que se
 * entrega no lugar são duas afirmações verdadeiras: a aritmética gravada confere com os resultados
 * gravados ({@link ReplayMode#AS_DECIDED}), e o motor atual, sobre a mesma evidência, conclui isto
 * ({@link ReplayMode#CURRENT_ENGINE}).
 *
 * <p><b>Nada é gravado.</b> A reexecução chama {@link RiskScoringService#evaluate}, que não persiste;
 * por consequência também não dispara {@code AssessmentCompletedListener}, não atualiza
 * {@code subject_risk_state} e não escreve na outbox. E nenhuma consulta paga acontece: a evidência
 * vem do banco pelos ids da V028, não de um bureau.
 *
 * <p><b>Precedência do veredito</b>, e o motivo de cada degrau:
 *
 * <ol>
 *   <li>{@link ReplayVerdict#TRAIL_INCONSISTENT} vence tudo. A reconferência aritmética não depende
 *       de reconstruir insumo nenhum, então ela é afirmável mesmo quando todo o resto falhou — e
 *       trilha que não fecha consigo mesma é o achado mais grave que este endpoint pode produzir.
 *   <li>{@link ReplayVerdict#DEGRADED} vem antes de qualquer conclusão sobre o motor. Dizer
 *       "o motor mudou de opinião" quando a causa é falta de insumo é pior que não responder.
 *   <li>Só então {@link ReplayVerdict#SAME_DECISION} / {@link ReplayVerdict#DIFFERENT_DECISION}.
 * </ol>
 */
@Service
public class DecisionReplayService {

  private final AssessmentService assessments;
  private final RiskScoreQueryService riskScores;
  private final ScreeningQueryService screenings;
  private final ReplayContextRebuilder rebuilder;
  private final RiskScoringService scoringService;
  private final Map<String, Set<ContextInput>> requisitosPorRegra;

  public DecisionReplayService(
      AssessmentService assessments,
      RiskScoreQueryService riskScores,
      ScreeningQueryService screenings,
      ReplayContextRebuilder rebuilder,
      RiskScoringService scoringService,
      List<RiskRule> rules) {
    this.assessments = assessments;
    this.riskScores = riskScores;
    this.screenings = screenings;
    this.rebuilder = rebuilder;
    this.scoringService = scoringService;
    // Um mapa código → insumos declarados. É o que permite marcar uma regra como NOT_REPLAYABLE em
    // vez de reportar o "não disparou" que ela devolveria rodando sobre insumo ausente.
    this.requisitosPorRegra =
        rules.stream()
            .collect(
                Collectors.toUnmodifiableMap(RiskRule::code, RiskRule::requires, (a, b) -> a));
  }

  public DecisionReplay replay(AssessmentId id, String tenantId, ReplayMode mode) {
    // Escopo de tenant primeiro: fora do tenant, 404 — nunca "existe mas não é seu", que
    // transformaria o endpoint em oráculo de id.
    Assessment assessment = assessments.get(id, tenantId);
    RiskScore score =
        riskScores
            .latestFor(id.asString())
            .orElseThrow(() -> new DecisionNotReplayableException(id.asString()));

    ArithmeticCheck arithmetic = ArithmeticCheck.of(score);
    RecordedDecision recorded = recorded(score);
    Map<String, EvaluatedRule> gravadas = gravadasPorCodigo(score);

    List<ReconstructionGap> gaps = new ArrayList<>();
    if (score.evaluated().isEmpty()) {
      gaps.add(
          ReconstructionGap.trail(
              GapKind.EVALUATED_TRAIL_ABSENT,
              "A decisão gravou apenas as regras que dispararam (anterior à migration V028); não há "
                  + "como provar que as demais rodaram e passaram"));
    }

    if (mode == ReplayMode.AS_DECIDED) {
      List<ReplayedRule> rules = gravadas.values().stream().map(DecisionReplayService::comoGravada).toList();
      return new DecisionReplay(
          id.asString(), mode, veredito(arithmetic, gaps, rules, mode), recorded, null,
          arithmetic, rules, gaps);
    }

    RebuiltContext rebuilt = rebuilder.rebuild(assessment, score);
    gaps.addAll(rebuilt.gaps());
    RiskDecision atual = scoringService.evaluate(rebuilt.context());

    boolean trilhaCompleta = !score.evaluated().isEmpty();
    List<ReplayedRule> rules =
        compara(gravadas, atual.evaluated(), rebuilt.unreliable(), trilhaCompleta);

    ReplayedDecision replayed =
        new ReplayedDecision(
            atual.level(), atual.totalScore(), atual.recommendation(), atual.engineVersion());
    return new DecisionReplay(
        id.asString(), mode, veredito(arithmetic, gaps, rules, mode), recorded, replayed,
        arithmetic, rules, gaps);
  }

  private RecordedDecision recorded(RiskScore score) {
    Map<String, String> versoes =
        screenings
            .findById(score.screeningResultId())
            .map(ScreeningResult::sources)
            .orElseGet(Map::of);
    return new RecordedDecision(
        score.level(),
        score.totalScore(),
        score.recommendation(),
        score.engineVersion(),
        score.scoredAt(),
        score.identityCheckId(),
        score.screeningResultId(),
        versoes);
  }

  /**
   * As regras da decisão gravada, por código.
   *
   * <p>Prefere {@code evaluated_json}, que tem <b>todas</b> as regras com o desfecho de cada uma.
   * Decisão anterior à V028 só tem as que dispararam: dá para reconstruí-las como
   * {@code TRIGGERED}, e a ausência das outras vira lacuna declarada — nunca "passou".
   */
  private static Map<String, EvaluatedRule> gravadasPorCodigo(RiskScore score) {
    if (!score.evaluated().isEmpty()) {
      return score.evaluated().stream()
          .collect(
              Collectors.toMap(
                  EvaluatedRule::ruleCode, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }
    return score.results().stream()
        .collect(
            Collectors.toMap(
                RiskResult::ruleCode,
                r -> EvaluatedRule.triggered(r.ruleCode(), r, Map.of()),
                (a, b) -> a,
                LinkedHashMap::new));
  }

  private static ReplayedRule comoGravada(EvaluatedRule gravada) {
    RiskResult r = gravada.result();
    return new ReplayedRule(
        gravada.ruleCode(),
        gravada.outcome(),
        r == null ? null : r.score(),
        r == null ? null : r.reason(),
        gravada.parameters(),
        null,
        null,
        null,
        RuleComparison.NOT_COMPARED,
        Set.of());
  }

  private List<ReplayedRule> compara(
      Map<String, EvaluatedRule> gravadas,
      List<EvaluatedRule> atuais,
      Set<ContextInput> naoConfiaveis,
      boolean trilhaCompleta) {

    Map<String, EvaluatedRule> porCodigoAtual =
        atuais.stream()
            .collect(
                Collectors.toMap(
                    EvaluatedRule::ruleCode, Function.identity(), (a, b) -> a, LinkedHashMap::new));

    Set<String> codigos = new LinkedHashSet<>(gravadas.keySet());
    codigos.addAll(porCodigoAtual.keySet());

    List<ReplayedRule> saida = new ArrayList<>();
    for (String codigo : codigos) {
      EvaluatedRule gravada = gravadas.get(codigo);
      EvaluatedRule atual = porCodigoAtual.get(codigo);

      Set<ContextInput> faltando =
          requisitosPorRegra.getOrDefault(codigo, Set.of()).stream()
              .filter(naoConfiaveis::contains)
              .collect(Collectors.toCollection(LinkedHashSet::new));

      RiskResult rg = gravada == null ? null : gravada.result();
      RiskResult ra = atual == null ? null : atual.result();

      RuleComparison comparacao;
      boolean publicaAtual = true;
      if (!faltando.isEmpty()) {
        // Ela até rodou — sobre insumo ausente. Publicar o resultado dessa execução sugeriria uma
        // conclusão que o dado não sustenta, então ele não sai.
        comparacao = RuleComparison.NOT_REPLAYABLE;
        publicaAtual = false;
      } else if (atual == null) {
        comparacao = RuleComparison.REMOVED;
        publicaAtual = false;
      } else if (gravada == null) {
        // Sem a trilha completa não dá para afirmar que a regra é nova: ela pode ter rodado e
        // passado sem ser gravada, que é justamente o que a V028 passou a registrar.
        comparacao = trilhaCompleta ? RuleComparison.ADDED : RuleComparison.NOT_COMPARED;
      } else if (gravada.outcome() != atual.outcome()) {
        comparacao = RuleComparison.OUTCOME_CHANGED;
      } else if (pontos(rg) != pontos(ra)) {
        comparacao = RuleComparison.SCORE_CHANGED;
      } else {
        comparacao = RuleComparison.SAME;
      }

      saida.add(
          new ReplayedRule(
              codigo,
              gravada == null ? null : gravada.outcome(),
              rg == null ? null : rg.score(),
              rg == null ? null : rg.reason(),
              gravada == null ? Map.of() : gravada.parameters(),
              publicaAtual && atual != null ? atual.outcome() : null,
              publicaAtual && ra != null ? ra.score() : null,
              publicaAtual && ra != null ? ra.reason() : null,
              comparacao,
              faltando));
    }
    return List.copyOf(saida);
  }

  private static int pontos(RiskResult result) {
    return result == null ? 0 : result.score();
  }

  private static ReplayVerdict veredito(
      ArithmeticCheck arithmetic,
      List<ReconstructionGap> gaps,
      List<ReplayedRule> rules,
      ReplayMode mode) {
    if (!arithmetic.consistent()) {
      return ReplayVerdict.TRAIL_INCONSISTENT;
    }
    boolean degradado =
        !gaps.isEmpty() || rules.stream().anyMatch(rule -> !rule.replayable());
    if (degradado) {
      return ReplayVerdict.DEGRADED;
    }
    if (mode == ReplayMode.AS_DECIDED) {
      return ReplayVerdict.REPRODUCED;
    }
    return rules.stream().anyMatch(ReplayedRule::differs)
        ? ReplayVerdict.DIFFERENT_DECISION
        : ReplayVerdict.SAME_DECISION;
  }
}
