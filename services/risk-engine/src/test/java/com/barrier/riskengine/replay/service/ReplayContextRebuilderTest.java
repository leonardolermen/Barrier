package com.barrier.riskengine.replay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.service.AssuranceService;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.service.IdentityCheckQueryService;
import com.barrier.riskengine.replay.domain.GapKind;
import com.barrier.riskengine.replay.domain.ReconstructionGap;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.service.ScreeningQueryService;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A lacuna tem de ser <b>apurada</b>. Declarar lacuna onde não há treina o leitor a ignorar o campo;
 * deixar de declarar onde há faz o replay atribuir falta de dado a mudança de motor.
 */
@ExtendWith(MockitoExtension.class)
class ReplayContextRebuilderTest {

  private static final String TENANT = "default";
  private static final Instant DECIDIDA_EM = Instant.parse("2026-03-01T10:00:00Z");

  @Mock IdentityCheckQueryService identityChecks;
  @Mock ScreeningQueryService screenings;
  @Mock SubjectProfileService profiles;
  @Mock AssuranceService assurance;

  private ReplayContextRebuilder rebuilder;
  private UUID identityId;
  private UUID screeningId;

  @BeforeEach
  void setUp() {
    rebuilder = new ReplayContextRebuilder(identityChecks, screenings, profiles, assurance);
    identityId = UUID.randomUUID();
    screeningId = UUID.randomUUID();
    lenient()
        .when(identityChecks.findById(identityId))
        .thenReturn(Optional.of(IdentityCheck.create("aid", IdentityStatus.VERIFIED, "stub", "ok")));
    lenient()
        .when(screenings.findById(screeningId))
        .thenReturn(Optional.of(ScreeningResult.of("aid", List.of())));
    lenient().when(profiles.findDeclared(any(), eq(TENANT))).thenReturn(Optional.empty());
    lenient().when(assurance.latest(any(), eq(TENANT), any())).thenReturn(Optional.empty());
    lenient().when(assurance.attempts(any(), eq(TENANT), eq(AssuranceKind.BIOMETRIC))).thenReturn(0L);
  }

  private static Assessment avaliacao(DocumentType tipo) {
    String documento = tipo == DocumentType.CPF ? "52998224725" : "19131243000197";
    return Assessment.submit(TENANT, UUID.randomUUID().toString(), tipo, documento, "Fulano");
  }

  private RiskScore score(UUID identity, UUID screening) {
    return new RiskScore(
        UUID.randomUUID(), "aid", RiskLevel.LOW, 0, RiskRecommendation.APPROVE,
        List.of(), List.of(), identity, screening, "barrier-risk-rules/1.7.0", DECIDIDA_EM);
  }

  private static SubjectProfile perfilAtualizadoEm(UUID subjectId, Instant quando) {
    SubjectProfile blank = SubjectProfile.blank(subjectId, TENANT);
    return new SubjectProfile(
        blank.id(), subjectId, TENANT, null, null, null, null, null, null, null, null, null, null,
        null, null, null, List.of(), quando, quando);
  }

  @Test
  void pf_com_evidencia_completa_e_sem_cadastro_nao_tem_lacuna() {
    Assessment assessment = avaliacao(DocumentType.CPF);

    RebuiltContext rebuilt = rebuilder.rebuild(assessment, score(identityId, screeningId));

    assertThat(rebuilt.gaps()).isEmpty();
    assertThat(rebuilt.unreliable()).isEmpty();
    assertThat(rebuilt.context().identity()).isNotNull();
    assertThat(rebuilt.context().screening()).isNotNull();
  }

  @Test
  void cadastro_inexistente_nao_conta_como_cadastro_alterado() {
    // SubjectProfile.blank nasce com updatedAt = agora. Sem distinguir ausência de alteração,
    // todo subject sem cadastro sairia com PROFILE_CHANGED_SINCE — lacuna inventada.
    Assessment assessment = avaliacao(DocumentType.CPF);
    when(profiles.findDeclared(any(), eq(TENANT))).thenReturn(Optional.empty());

    RebuiltContext rebuilt = rebuilder.rebuild(assessment, score(identityId, screeningId));

    assertThat(rebuilt.gaps()).extracting(ReconstructionGap::kind).doesNotContain(GapKind.PROFILE_CHANGED_SINCE);
  }

  @Test
  void cadastro_intocado_desde_a_decisao_nao_e_lacuna() {
    Assessment assessment = avaliacao(DocumentType.CPF);
    UUID subjectId = UUID.fromString(assessment.subjectId());
    when(profiles.findDeclared(subjectId, TENANT))
        .thenReturn(Optional.of(perfilAtualizadoEm(subjectId, DECIDIDA_EM.minus(Duration.ofDays(3)))));

    RebuiltContext rebuilt = rebuilder.rebuild(assessment, score(identityId, screeningId));

    assertThat(rebuilt.unreliable()).doesNotContain(ContextInput.PROFILE);
  }

  @Test
  void cadastro_alterado_depois_da_decisao_e_lacuna() {
    Assessment assessment = avaliacao(DocumentType.CPF);
    UUID subjectId = UUID.fromString(assessment.subjectId());
    when(profiles.findDeclared(subjectId, TENANT))
        .thenReturn(Optional.of(perfilAtualizadoEm(subjectId, DECIDIDA_EM.plus(Duration.ofDays(1)))));

    RebuiltContext rebuilt = rebuilder.rebuild(assessment, score(identityId, screeningId));

    assertThat(rebuilt.unreliable()).contains(ContextInput.PROFILE);
    assertThat(rebuilt.gaps()).extracting(ReconstructionGap::kind).contains(GapKind.PROFILE_CHANGED_SINCE);
  }

  @Test
  void pj_sempre_tem_lacuna_de_qsa_porque_o_perfil_da_empresa_nao_e_persistido() {
    Assessment assessment = avaliacao(DocumentType.CNPJ);

    RebuiltContext rebuilt = rebuilder.rebuild(assessment, score(identityId, screeningId));

    assertThat(rebuilt.unreliable()).contains(ContextInput.COMPANY);
    assertThat(rebuilt.gaps()).extracting(ReconstructionGap::kind).contains(GapKind.COMPANY_NOT_PERSISTED);
    assertThat(rebuilt.context().company()).isNull();
  }

  @Test
  void decisao_anterior_a_v028_declara_evidencia_ausente() {
    Assessment assessment = avaliacao(DocumentType.CPF);

    RebuiltContext rebuilt = rebuilder.rebuild(assessment, score(null, null));

    assertThat(rebuilt.unreliable()).contains(ContextInput.IDENTITY, ContextInput.SCREENING);
    assertThat(rebuilt.gaps())
        .extracting(ReconstructionGap::kind)
        .contains(GapKind.IDENTITY_EVIDENCE_MISSING, GapKind.SCREENING_EVIDENCE_MISSING);
  }

  @Test
  void tentativa_de_biometria_existente_torna_o_resumo_de_assurance_nao_reconstruivel() {
    // biometricAttempts é COUNT sobre janela que termina agora; não há como recontá-la como estava.
    Assessment assessment = avaliacao(DocumentType.CPF);
    when(assurance.attempts(any(), eq(TENANT), eq(AssuranceKind.BIOMETRIC))).thenReturn(3L);

    RebuiltContext rebuilt = rebuilder.rebuild(assessment, score(identityId, screeningId));

    assertThat(rebuilt.unreliable()).contains(ContextInput.ASSURANCE);
    assertThat(rebuilt.gaps()).extracting(ReconstructionGap::kind).contains(GapKind.ASSURANCE_WINDOW_RELATIVE);
  }

  @Test
  void subject_sem_assurance_nenhuma_nao_gera_lacuna() {
    Assessment assessment = avaliacao(DocumentType.CPF);

    RebuiltContext rebuilt = rebuilder.rebuild(assessment, score(identityId, screeningId));

    assertThat(rebuilt.unreliable()).doesNotContain(ContextInput.ASSURANCE);
  }
}
