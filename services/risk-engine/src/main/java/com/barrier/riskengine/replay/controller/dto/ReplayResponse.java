package com.barrier.riskengine.replay.controller.dto;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resposta do replay de decisão.
 *
 * <p>Contém deliberadamente <b>zero</b> dado pessoal: nem documento, nem nome, nem endereço. O que
 * sai são códigos de regra, pontuações, versões de motor e de lista, e os motivos que as próprias
 * regras já publicam como fator explicável no {@code GET /v1/assessments/&#123;id&#125;}. É a mesma
 * regra do alerta de monitoramento — descrever sem identificar é o que permite levar a resposta para
 * fora do controle de acesso do banco, e um dossiê de auditoria circula.
 *
 * @param verdict conclusão. {@code TRAIL_INCONSISTENT} é o achado grave; {@code DEGRADED} significa
 *     que faltou insumo e nada pode ser afirmado sobre o motor atual
 * @param gaps o que não foi reconstruído. Lista vazia é a afirmação forte
 */
public record ReplayResponse(
    String assessmentId,
    String mode,
    String verdict,
    RecordedDecisionDto recorded,
    ReplayedDecisionDto replayed,
    ArithmeticDto arithmetic,
    List<RuleDto> rules,
    List<GapDto> gaps) {

  /** O que foi decidido, e com base em qual evidência. */
  public record RecordedDecisionDto(
      RiskLevel level,
      int score,
      RiskRecommendation recommendation,
      String engineVersion,
      Instant decidedAt,
      UUID identityCheckId,
      UUID screeningResultId,
      Map<String, String> watchlistVersions) {}

  /** O que o motor de hoje conclui sobre a mesma evidência; ausente no modo {@code AS_DECIDED}. */
  public record ReplayedDecisionDto(
      RiskLevel level, int score, RiskRecommendation recommendation, String engineVersion) {}

  /**
   * Reconferência da aritmética: soma, banda e recomendação recalculadas a partir dos resultados
   * gravados. {@code consistent=false} indica trilha adulterada ou corrompida.
   */
  public record ArithmeticDto(
      boolean consistent,
      int recordedScore,
      int recomputedScore,
      RiskLevel recordedLevel,
      RiskLevel recomputedLevel,
      RiskRecommendation recordedRecommendation,
      RiskRecommendation recomputedRecommendation) {}

  /**
   * Uma regra, como constou na decisão e como responde hoje.
   *
   * @param recordedParameters parâmetros efetivos da época — {@code tenant_risk_config} é mutável e
   *     não responderia isso hoje
   * @param missingInputs insumos declarados pela regra que não foram reconstruídos; preenchido
   *     apenas quando {@code comparison} é {@code NOT_REPLAYABLE}
   * @param policy a política vigente sobre a regra no instante da decisão, com autoria
   */
  public record RuleDto(
      String ruleCode,
      String recordedOutcome,
      Integer recordedScore,
      String recordedReason,
      Map<String, String> recordedParameters,
      String replayedOutcome,
      Integer replayedScore,
      String replayedReason,
      String comparison,
      List<String> missingInputs,
      PolicyDto policy) {}

  /**
   * A política que vigia sobre a regra quando a decisão foi tomada.
   *
   * <p>{@code registry} nulo significa que a autoria não é apurável: há alterações registradas, mas
   * todas posteriores à decisão, e {@code risk_rule_registry_history} guarda o estado <i>novo</i> de
   * cada mudança — o anterior à primeira não foi gravado por ninguém. O desfecho da regra naquela
   * avaliação continua conhecido (vem de {@code recordedOutcome}); o que falta é quem definiu a
   * política.
   */
  public record PolicyDto(RegistryPolicyDto registry, List<ParamAuthorshipDto> parameters) {}

  /**
   * Estado do registry no instante: se a regra estava ligada, com que criticidade e vigência, e quem
   * deixou assim. Ligar e desligar regra de risco é a operação mais sensível do sistema.
   */
  public record RegistryPolicyDto(
      boolean enabled,
      String criticality,
      Instant validFrom,
      Instant validUntil,
      String changedBy,
      Instant changedAt,
      String provenance) {}

  /**
   * Quem definiu o parâmetro que a regra usou. O <b>valor</b> vem de {@code recordedParameters}, que
   * a decisão já gravava — aqui está só a autoria, para não haver duas fontes do mesmo fato.
   */
  public record ParamAuthorshipDto(
      String paramKey,
      String source,
      String changedBy,
      Instant changedAt,
      String provenance) {}

  /** Um insumo que não pôde ser reconstruído, com o motivo. */
  public record GapDto(String kind, String input, String detail) {}
}
