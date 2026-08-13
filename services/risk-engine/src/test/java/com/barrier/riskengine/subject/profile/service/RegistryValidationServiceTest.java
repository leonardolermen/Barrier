package com.barrier.riskengine.subject.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.subject.profile.client.interfaces.OtpSender;
import com.barrier.riskengine.subject.profile.client.interfaces.RegistryValidationProvider;
import com.barrier.riskengine.subject.profile.domain.FieldVerification;
import com.barrier.riskengine.subject.profile.domain.RegistryValidationRequest;
import com.barrier.riskengine.subject.profile.domain.RegistryValidationResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.domain.VerifiableField;
import com.barrier.riskengine.subject.profile.domain.VerificationChallenge;
import com.barrier.riskengine.subject.profile.repository.interfaces.FieldVerificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * O Datavalid é cobrado por consulta: o comportamento sob teste aqui é <b>quando</b> a chamada
 * acontece, não só o que ela produz — um teste que passa com a chamada removida não vale nada (é
 * o defeito que esta etapa existe para fechar, ver relatório).
 */
class RegistryValidationServiceTest {

  private static final UUID SUBJECT = UUID.randomUUID();
  private static final String TENANT = "t1";
  private static final Instant AGORA = Instant.parse("2026-08-13T10:00:00Z");

  /** Repositório em memória — o que está sob teste é a orquestração, não o SQL. */
  private static final class FakeRepo implements FieldVerificationRepository {
    final List<FieldVerification> verifications = new ArrayList<>();

    @Override
    public void save(FieldVerification v) {
      verifications.removeIf(existing -> existing.field() == v.field());
      verifications.add(v);
    }

    @Override
    public List<FieldVerification> findBySubjectAndTenant(UUID subjectId, String tenantId) {
      return List.copyOf(verifications);
    }

    @Override
    public void saveChallenge(VerificationChallenge c) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<VerificationChallenge> findLatestChallenge(
        UUID subjectId, String tenantId, VerifiableField field) {
      return Optional.empty();
    }

    @Override
    public void updateChallenge(VerificationChallenge c) {
      throw new UnsupportedOperationException();
    }
  }

  private final FakeRepo repo = new FakeRepo();
  private final FieldVerificationService fieldVerificationService =
      new FieldVerificationService(
          repo,
          mock(OtpSender.class),
          Clock.fixed(AGORA, ZoneOffset.UTC),
          3,
          Duration.ofMinutes(10));

  private static SubjectProfile perfil(LocalDate nascimento, String cep) {
    SubjectProfile blank = SubjectProfile.blank(SUBJECT, TENANT);
    return new SubjectProfile(
        blank.id(),
        SUBJECT,
        TENANT,
        nascimento,
        null,
        null,
        null,
        null,
        cep == null
            ? null
            : new SubjectProfile.Address("Rua A", "10", null, "Centro", "Cidade", "SP", cep),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        blank.createdAt(),
        blank.updatedAt());
  }

  private RegistryValidationService service(RegistryValidationProvider provider, boolean enabled) {
    return new RegistryValidationService(provider, fieldVerificationService, enabled);
  }

  @Test
  void cadastroJaVerificadoPorOtpNaoChamaODatavalid() {
    RegistryValidationProvider provider = mock(RegistryValidationProvider.class);
    RegistryValidationService service = service(provider, true);

    Set<VerifiableField> resultado =
        service.verifyIfWorthwhile(
            SUBJECT,
            TENANT,
            "CPF",
            "11122233396",
            "Fulano",
            perfil(LocalDate.of(1990, 1, 1), "01000-000"),
            Set.of(VerifiableField.BIRTH_DATE),
            "assessment-1");

    verifyNoInteractions(provider);
    assertThat(resultado).containsExactly(VerifiableField.BIRTH_DATE);
  }

  @Test
  void nascimentoNaoDeclaradoNaoChamaODatavalid() {
    RegistryValidationProvider provider = mock(RegistryValidationProvider.class);
    RegistryValidationService service = service(provider, true);

    Set<VerifiableField> resultado =
        service.verifyIfWorthwhile(
            SUBJECT,
            TENANT,
            "CPF",
            "11122233396",
            "Fulano",
            perfil(null, "01000-000"),
            Set.of(),
            "assessment-1");

    verifyNoInteractions(provider);
    assertThat(resultado).isEmpty();
  }

  @Test
  void nascimentoDeclaradoENaoConferidoChamaUmaVezEGravaAVerificacao() {
    RegistryValidationProvider provider = mock(RegistryValidationProvider.class);
    RegistryValidationResult.Rfb rfb = new RegistryValidationResult.Rfb(1.0, null, true, true, true);
    when(provider.validate(any(UUID.class), any(String.class), any(RegistryValidationRequest.class)))
        .thenReturn(Optional.of(new RegistryValidationResult(true, false, rfb, null, "datavalid/v5")));
    RegistryValidationService service = service(provider, true);

    Set<VerifiableField> resultado =
        service.verifyIfWorthwhile(
            SUBJECT,
            TENANT,
            "CPF",
            "11122233396",
            "Fulano",
            perfil(LocalDate.of(1990, 1, 1), "01000-000"),
            Set.of(),
            "assessment-1");

    org.mockito.Mockito.verify(provider)
        .validate(any(UUID.class), any(String.class), any(RegistryValidationRequest.class));
    assertThat(resultado).containsExactly(VerifiableField.BIRTH_DATE);
    assertThat(repo.verifications).hasSize(1);
    assertThat(repo.verifications.getFirst().method())
        .isEqualTo(com.barrier.riskengine.subject.profile.domain.VerificationMethod.REGISTRY);
  }

  @Test
  void datavalidDivergeDoDeclaradoNaoGravaVerificacao() {
    RegistryValidationProvider provider = mock(RegistryValidationProvider.class);
    RegistryValidationResult.Rfb rfb = new RegistryValidationResult.Rfb(0.2, null, true, false, true);
    when(provider.validate(any(UUID.class), any(String.class), any(RegistryValidationRequest.class)))
        .thenReturn(Optional.of(new RegistryValidationResult(true, false, rfb, null, "datavalid/v5")));
    RegistryValidationService service = service(provider, true);

    Set<VerifiableField> resultado =
        service.verifyIfWorthwhile(
            SUBJECT,
            TENANT,
            "CPF",
            "11122233396",
            "Fulano",
            perfil(LocalDate.of(1990, 1, 1), "01000-000"),
            Set.of(),
            "assessment-1");

    assertThat(resultado).isEmpty();
    assertThat(repo.verifications).isEmpty();
  }

  @Test
  void provedorIndisponivelNaoGravaVerificacaoNemLancaExcecao() {
    RegistryValidationProvider provider = mock(RegistryValidationProvider.class);
    when(provider.validate(any(UUID.class), any(String.class), any(RegistryValidationRequest.class)))
        .thenReturn(Optional.empty());
    RegistryValidationService service = service(provider, true);

    Set<VerifiableField> resultado =
        service.verifyIfWorthwhile(
            SUBJECT,
            TENANT,
            "CPF",
            "11122233396",
            "Fulano",
            perfil(LocalDate.of(1990, 1, 1), "01000-000"),
            Set.of(),
            "assessment-1");

    assertThat(resultado).isEmpty();
    assertThat(repo.verifications).isEmpty();
  }

  @Test
  void flagDesligadaNaoChamaMesmoComNascimentoPendente() {
    RegistryValidationProvider provider = mock(RegistryValidationProvider.class);
    RegistryValidationService service = service(provider, false);

    Set<VerifiableField> resultado =
        service.verifyIfWorthwhile(
            SUBJECT,
            TENANT,
            "CPF",
            "11122233396",
            "Fulano",
            perfil(LocalDate.of(1990, 1, 1), "01000-000"),
            Set.of(),
            "assessment-1");

    verifyNoInteractions(provider);
    assertThat(resultado).isEmpty();
  }

  @Test
  void pessoaJuridicaNuncaChamaODatavalid() {
    RegistryValidationProvider provider = mock(RegistryValidationProvider.class);
    RegistryValidationService service = service(provider, true);

    Set<VerifiableField> resultado =
        service.verifyIfWorthwhile(
            SUBJECT,
            TENANT,
            "CNPJ",
            "11222333000181",
            "Empresa Ltda",
            perfil(LocalDate.of(1990, 1, 1), "01000-000"),
            Set.of(),
            "assessment-1");

    verifyNoInteractions(provider);
    assertThat(resultado).isEmpty();
  }
}
