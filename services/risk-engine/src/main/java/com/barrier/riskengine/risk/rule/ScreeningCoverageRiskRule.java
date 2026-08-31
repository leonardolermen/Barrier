package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.screening.client.interfaces.NegativeMediaProvider;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.watchlist.WatchlistImportStatus;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Impede aprovação automática quando o screening rodou <b>sem cobertura</b>.
 *
 * <p>Um {@code ScreeningResult} vazio é ambíguo: pode significar "consultamos as listas e o cliente
 * está limpo" ou "não havia lista nenhuma para consultar". O motor tratava os dois casos como o
 * primeiro. Se a importação da CGU ou da OFAC falha — portal fora do ar, CSV com layout novo, ZIP
 * truncado — a tabela fica vazia, todo screening responde CLEAR e toda avaliação é aprovada, com a
 * trilha registrando "sem apontamentos". É a diferença entre ausência de evidência e evidência de
 * ausência, e ela decide se alguém sancionado abre conta.
 *
 * <p>Força REVIEW em vez de REJECT: o problema é nosso, não do cliente. A avaliação vai para
 * análise humana e pode ser reprocessada quando a cobertura voltar.
 *
 * <p><b>{@code SANCTION} e {@code PEP} são exigência incondicional</b> — sempre checadas contra
 * {@link WatchlistImportStatus#coverage()}, sem exceção.
 *
 * <p><b>{@code ADVERSE_MEDIA} é condicional à existência de um {@link NegativeMediaProvider}
 * autoritativo.</b> Primeira versão desta regra exigia {@code ADVERSE_MEDIA} incondicionalmente,
 * igual às outras duas — e quebrou em produção de um jeito pior do que o fail-open que existia
 * para fechar: como {@code ADVERSE_MEDIA} nunca é populada em {@code WatchlistImportStatus} (mídia
 * negativa é {@link NegativeMediaProvider}, consultada ao vivo por avaliação, não importada como
 * {@code WatchlistSource}), a cobertura estava <i>sempre</i> ausente, e a regra pontuava 100% das
 * avaliações — recriando o problema que motivou o status {@code SOLICITAR_DOCUMENTO}
 * (7501 de 7529 avaliações em fila de EDD por ruído, cegando o time de operações; ver
 * {@code plano-remediacao-auditoria.md}). Sem provedor contratado (só o
 * {@code StubNegativeMediaProvider}, {@code authoritative() == false}), a ausência de cobertura é
 * um fato conhecido e constante para toda a base — alarme por avaliação não informa nada; o
 * {@code WatchlistReadinessGuard} já avisa isso uma vez, no startup, que é o lugar certo. Com um
 * provedor autoritativo contratado, a exigência entra: é controle que deveria estar rodando (ou
 * que a infraestrutura de importação ainda não sabe medir) e não está confirmado, tratamento igual
 * ao de sanção e PEP.
 */
@Component
public class ScreeningCoverageRiskRule implements RiskRule {

  private static final String RULE_CODE = "SCREENING_COVERAGE";
  // DEBARMENT fica de fora porque é apetite de risco (ver DebarmentRiskRule), não obrigação
  // regulatória.
  private static final Set<MatchType> BASE_REQUIRED = Set.of(MatchType.SANCTION, MatchType.PEP);
  private static final int SCORE = 300;

  private final WatchlistImportStatus status;
  private final List<NegativeMediaProvider> negativeMediaProviders;

  /**
   * Construtor de conveniência: equivale a "nenhum provedor de mídia negativa autoritativo", que
   * é o comportamento seguro por padrão (exige só {@code SANCTION}/{@code PEP}).
   */
  public ScreeningCoverageRiskRule(WatchlistImportStatus status) {
    this(status, List.of());
  }

  @Autowired
  public ScreeningCoverageRiskRule(
      WatchlistImportStatus status, List<NegativeMediaProvider> negativeMediaProviders) {
    this.status = status;
    this.negativeMediaProviders = negativeMediaProviders;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    Set<MatchType> covered = status.coverage();
    Set<MatchType> required = required();
    List<String> missing =
        required.stream().filter(type -> !covered.contains(type)).map(Enum::name).sorted().toList();

    if (missing.isEmpty()) {
      return RiskResult.notApplicable(RULE_CODE);
    }
    return new RiskResult(
        RULE_CODE,
        SCORE,
        Severity.HIGH,
        "Screening executado sem cobertura de " + String.join(", ", missing),
        missing.stream().map(type -> "cobertura ausente:" + type).toList(),
        RiskRecommendation.REVIEW);
  }

  private Set<MatchType> required() {
    boolean hasAuthoritativeNegativeMedia =
        negativeMediaProviders.stream().anyMatch(NegativeMediaProvider::authoritative);
    if (!hasAuthoritativeNegativeMedia) {
      return BASE_REQUIRED;
    }
    return Stream.concat(BASE_REQUIRED.stream(), Stream.of(MatchType.ADVERSE_MEDIA))
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public String code() {
    return RULE_CODE;
  }

  @Override
  // Vazio de propósito: esta regra decide sobre o estado da importação de listas
  // (WatchlistImportStatus), não sobre nenhum campo do contexto do cliente.
  public Set<ContextInput> requires() {
    return Set.of();
  }
}
