package com.barrier.riskengine.replay.controller.dto;

import com.barrier.riskengine.policy.ParamAuthorship;
import com.barrier.riskengine.policy.RegistryPolicyState;
import com.barrier.riskengine.replay.domain.ArithmeticCheck;
import com.barrier.riskengine.replay.domain.DecisionReplay;
import com.barrier.riskengine.replay.domain.RecordedDecision;
import com.barrier.riskengine.replay.domain.ReconstructionGap;
import com.barrier.riskengine.replay.domain.ReplayedDecision;
import com.barrier.riskengine.replay.domain.ReplayedRule;
import com.barrier.riskengine.replay.domain.RulePolicy;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import java.util.Comparator;

/** Domínio → DTO. Escrito à mão, no padrão dos demais mappers do repositório. */
public final class ReplayDtoMapper {

  private ReplayDtoMapper() {}

  public static ReplayResponse toResponse(DecisionReplay replay) {
    return new ReplayResponse(
        replay.assessmentId(),
        replay.mode().name(),
        replay.verdict().name(),
        toDto(replay.recordedDecision()),
        toDto(replay.replayedDecision()),
        toDto(replay.arithmetic()),
        replay.rules().stream()
            // Ordem estável por código: o consumidor natural desta resposta é um diff, e um diff
            // sobre lista com ordem instável produz ruído que parece mudança.
            .sorted(Comparator.comparing(ReplayedRule::ruleCode))
            .map(ReplayDtoMapper::toDto)
            .toList(),
        replay.gaps().stream().map(ReplayDtoMapper::toDto).toList());
  }

  private static ReplayResponse.RecordedDecisionDto toDto(RecordedDecision recorded) {
    return new ReplayResponse.RecordedDecisionDto(
        recorded.level(),
        recorded.score(),
        recorded.recommendation(),
        recorded.engineVersion(),
        recorded.policyVersion(),
        recorded.decidedAt(),
        recorded.identityCheckId(),
        recorded.screeningResultId(),
        recorded.watchlistVersions());
  }

  private static ReplayResponse.ReplayedDecisionDto toDto(ReplayedDecision replayed) {
    return replayed == null
        ? null
        : new ReplayResponse.ReplayedDecisionDto(
            replayed.level(),
            replayed.score(),
            replayed.recommendation(),
            replayed.engineVersion(),
            replayed.policyVersion());
  }

  private static ReplayResponse.ArithmeticDto toDto(ArithmeticCheck check) {
    return new ReplayResponse.ArithmeticDto(
        check.consistent(),
        check.recordedScore(),
        check.recomputedScore(),
        check.recordedLevel(),
        check.recomputedLevel(),
        check.recordedRecommendation(),
        check.recomputedRecommendation());
  }

  private static ReplayResponse.RuleDto toDto(ReplayedRule rule) {
    return new ReplayResponse.RuleDto(
        rule.ruleCode(),
        rule.recordedOutcome() == null ? null : rule.recordedOutcome().name(),
        rule.recordedScore(),
        rule.recordedReason(),
        rule.recordedParameters(),
        rule.replayedOutcome() == null ? null : rule.replayedOutcome().name(),
        rule.replayedScore(),
        rule.replayedReason(),
        rule.comparison().name(),
        rule.missingInputs().stream().map(ContextInput::name).sorted().toList(),
        toDto(rule.policy()));
  }

  private static ReplayResponse.PolicyDto toDto(RulePolicy policy) {
    if (policy == null) {
      return new ReplayResponse.PolicyDto(null, java.util.List.of());
    }
    return new ReplayResponse.PolicyDto(
        toDto(policy.registry()), policy.parameters().stream().map(ReplayDtoMapper::toDto).toList());
  }

  private static ReplayResponse.RegistryPolicyDto toDto(RegistryPolicyState state) {
    return state == null
        ? null
        : new ReplayResponse.RegistryPolicyDto(
            state.enabled(),
            state.criticality(),
            state.validFrom(),
            state.validUntil(),
            state.changedBy(),
            state.changedAt(),
            state.provenance().name());
  }

  private static ReplayResponse.ParamAuthorshipDto toDto(ParamAuthorship authorship) {
    return new ReplayResponse.ParamAuthorshipDto(
        authorship.paramKey(),
        authorship.source().name(),
        authorship.changedBy(),
        authorship.changedAt(),
        authorship.provenance().name());
  }

  private static ReplayResponse.GapDto toDto(ReconstructionGap gap) {
    return new ReplayResponse.GapDto(
        gap.kind().name(), gap.input() == null ? null : gap.input().name(), gap.detail());
  }
}
