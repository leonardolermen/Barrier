package com.barrier.riskengine.replay.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.service.AssuranceService;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.service.IdentityCheckQueryService;
import com.barrier.riskengine.replay.domain.GapKind;
import com.barrier.riskengine.replay.domain.ReconstructionGap;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.rule.context.AssuranceSummary;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.service.ScreeningQueryService;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Remonta o {@code RiskContext} de uma decisão a partir do que está gravado — e, principalmente,
 * <b>declara o que não conseguiu remontar</b>.
 *
 * <p>Nenhuma chamada de rede acontece aqui: identidade e screening vêm do banco pelos ids exatos que
 * a V028 gravou, não de uma consulta nova. É o que garante que replayar não gasta consulta paga de
 * bureau, e a garantia é estrutural — este componente não conhece nenhum {@code BureauProvider}.
 *
 * <p><b>A lacuna é apurada, não presumida.</b> Cada insumo tem um teste próprio e barato:
 *
 * <ul>
 *   <li><b>identidade / screening</b> — reconstruíveis. Só faltam em decisão anterior à V028 (id
 *       nulo) ou se a linha sumiu.
 *   <li><b>company</b> — o {@code CompanyProfile} é transiente e nunca foi persistido, então é
 *       lacuna sempre que a avaliação é de PJ. Para CPF <b>não</b> é lacuna: ali {@code company} era
 *       nulo na decisão também, e reportar uma lacuna que não existe treina o leitor a ignorar o
 *       campo.
 *   <li><b>cadastro</b> — {@code subject_profiles} não tem histórico, mas tem {@code updated_at}: se
 *       o cadastro não foi tocado depois da decisão, o que se lê hoje <b>é</b> o que a decisão viu, e
 *       não há lacuna. É o que mantém {@code SAME_DECISION} alcançável no caso comum em vez de
 *       transformar todo replay em "degradado".
 *   <li><b>assurance</b> — lacuna sempre que existe qualquer verificação, porque
 *       {@code AssuranceSummary.biometricAttempts} é contagem sobre janela que termina <b>agora</b>.
 *       Sem verificação nenhuma o resumo é trivialmente o mesmo (os checks são acervo, não são
 *       apagados), e aí não há lacuna.
 * </ul>
 */
@Service
public class ReplayContextRebuilder {

  private final IdentityCheckQueryService identityChecks;
  private final ScreeningQueryService screenings;
  private final SubjectProfileService profiles;
  private final AssuranceService assurance;

  public ReplayContextRebuilder(
      IdentityCheckQueryService identityChecks,
      ScreeningQueryService screenings,
      SubjectProfileService profiles,
      AssuranceService assurance) {
    this.identityChecks = identityChecks;
    this.screenings = screenings;
    this.profiles = profiles;
    this.assurance = assurance;
  }

  public RebuiltContext rebuild(Assessment assessment, RiskScore score) {
    List<ReconstructionGap> gaps = new ArrayList<>();
    Set<ContextInput> unreliable = EnumSet.noneOf(ContextInput.class);

    IdentityCheck identity = identityChecks.findById(score.identityCheckId()).orElse(null);
    if (identity == null) {
      unreliable.add(ContextInput.IDENTITY);
      gaps.add(
          ReconstructionGap.of(
              GapKind.IDENTITY_EVIDENCE_MISSING,
              ContextInput.IDENTITY,
              score.identityCheckId() == null
                  ? "A decisão não registrou identity_check_id (anterior à migration V028)"
                  : "identity_check_id registrado, mas a verificação não foi encontrada"));
    }

    ScreeningResult screening = screenings.findById(score.screeningResultId()).orElse(null);
    if (screening == null) {
      unreliable.add(ContextInput.SCREENING);
      gaps.add(
          ReconstructionGap.of(
              GapKind.SCREENING_EVIDENCE_MISSING,
              ContextInput.SCREENING,
              score.screeningResultId() == null
                  ? "A decisão não registrou screening_result_id (anterior à migration V028)"
                  : "screening_result_id registrado, mas o screening não foi encontrado"));
    }

    if (assessment.documentType() == DocumentType.CNPJ) {
      unreliable.add(ContextInput.COMPANY);
      gaps.add(
          ReconstructionGap.of(
              GapKind.COMPANY_NOT_PERSISTED,
              ContextInput.COMPANY,
              "O perfil de PJ do bureau (abertura, CNAE, QSA) é transiente e não é persistido na "
                  + "decisão; não há de onde recuperá-lo como estava"));
    }

    UUID subjectId = UUID.fromString(assessment.subjectId());
    // findDeclared, e não find: `find` devolve um cadastro em branco com `updatedAt = agora` quando
    // não há linha, o que faria "nunca teve cadastro" parecer "cadastro alterado agora mesmo" — e
    // todo replay de subject sem cadastro sairia degradado por uma lacuna que não existe.
    Optional<SubjectProfile> declarado = profiles.findDeclared(subjectId, assessment.tenantId());
    SubjectProfile profile =
        declarado.orElseGet(() -> SubjectProfile.blank(subjectId, assessment.tenantId()));
    if (declarado.isPresent() && changedAfter(profile.updatedAt(), score.scoredAt())) {
      unreliable.add(ContextInput.PROFILE);
      gaps.add(
          ReconstructionGap.of(
              GapKind.PROFILE_CHANGED_SINCE,
              ContextInput.PROFILE,
              "O cadastro foi alterado depois desta decisão e subject_profiles não guarda "
                  + "histórico; o cadastro lido agora não é o que a decisão usou"));
    }

    AssuranceSummary assuranceSummary = assuranceSummary(subjectId, assessment.tenantId());
    if (hasAssurance(assuranceSummary)) {
      unreliable.add(ContextInput.ASSURANCE);
      gaps.add(
          ReconstructionGap.of(
              GapKind.ASSURANCE_WINDOW_RELATIVE,
              ContextInput.ASSURANCE,
              "A contagem de tentativas de biometria é apurada sobre uma janela que termina agora, "
                  + "não no instante da decisão"));
    }

    RiskContext context =
        new RiskContext(
            assessment.id().asString(),
            assessment.tenantId(),
            identity,
            screening,
            null, // sempre: ver COMPANY_NOT_PERSISTED
            profile,
            assuranceSummary);
    return new RebuiltContext(context, unreliable, gaps);
  }

  /** Cadastro em branco nasce sem instante de atualização — nada foi declarado, nada mudou. */
  private static boolean changedAfter(Instant updatedAt, Instant decidedAt) {
    return updatedAt != null && decidedAt != null && updatedAt.isAfter(decidedAt);
  }

  private AssuranceSummary assuranceSummary(UUID subjectId, String tenantId) {
    return new AssuranceSummary(
        assurance.latest(subjectId, tenantId, AssuranceKind.DOCUMENT).orElse(null),
        assurance.latest(subjectId, tenantId, AssuranceKind.BIOMETRIC).orElse(null),
        assurance.attempts(subjectId, tenantId, AssuranceKind.BIOMETRIC));
  }

  private static boolean hasAssurance(AssuranceSummary summary) {
    return summary.document() != null
        || summary.biometric() != null
        || summary.biometricAttempts() > 0;
  }
}
