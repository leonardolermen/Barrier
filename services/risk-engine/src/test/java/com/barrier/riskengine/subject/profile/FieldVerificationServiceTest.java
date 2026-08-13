package com.barrier.riskengine.subject.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.subject.profile.client.interfaces.OtpSender;
import com.barrier.riskengine.subject.profile.domain.FieldVerification;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.domain.VerifiableField;
import com.barrier.riskengine.subject.profile.domain.VerificationChallenge;
import com.barrier.riskengine.subject.profile.domain.VerificationMethod;
import com.barrier.riskengine.subject.profile.repository.interfaces.FieldVerificationRepository;
import com.barrier.riskengine.subject.profile.service.FieldVerificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FieldVerificationServiceTest {

  private static final Instant AGORA = Instant.parse("2026-08-12T10:00:00Z");
  private static final UUID SUBJECT = UUID.randomUUID();
  private static final String TENANT = "t1";

  /** Repositório em memória: o que está sob teste é a regra, não o SQL. */
  private static final class Fake implements FieldVerificationRepository {
    final List<FieldVerification> verifications = new ArrayList<>();
    final List<VerificationChallenge> challenges = new ArrayList<>();
    String enviado;

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
      challenges.add(c);
    }

    @Override
    public Optional<VerificationChallenge> findLatestChallenge(
        UUID subjectId, String tenantId, VerifiableField field) {
      return challenges.stream().filter(c -> c.field() == field).reduce((a, b) -> b);
    }

    @Override
    public void updateChallenge(VerificationChallenge c) {
      challenges.replaceAll(existing -> existing.id().equals(c.id()) ? c : existing);
    }
  }

  private final Fake repo = new Fake();

  private FieldVerificationService service(Instant agora) {
    return new FieldVerificationService(
        repo,
        new OtpSender() {
          @Override
          public void send(VerifiableField field, String target, String code) {
            repo.enviado = code;
          }

          @Override
          public String name() {
            return "fake";
          }
        },
        Clock.fixed(agora, ZoneOffset.UTC),
        3,
        Duration.ofMinutes(10));
  }

  private static SubjectProfile perfil(String telefone, LocalDate nascimento) {
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
        null,
        telefone,
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

  @Test
  void codigoCorretoVerificaOTelefone() {
    FieldVerificationService service = service(AGORA);
    service.challenge(SUBJECT, TENANT, VerifiableField.PHONE, perfil("11999998888", null));

    assertThat(service.confirm(SUBJECT, TENANT, VerifiableField.PHONE, repo.enviado)).isTrue();
    assertThat(service.verifiedFields(SUBJECT, TENANT, perfil("11999998888", null)))
        .containsExactly(VerifiableField.PHONE);
  }

  /** O código guardado é hash: quem lê a tabela não confirma telefone de cliente nenhum. */
  @Test
  void oCodigoNaoEGuardadoEmClaro() {
    service(AGORA).challenge(SUBJECT, TENANT, VerifiableField.PHONE, perfil("11999998888", null));

    assertThat(repo.challenges.getFirst().codeHash()).isNotEqualTo(repo.enviado);
    assertThat(repo.challenges.getFirst().codeHash()).hasSize(64);
  }

  /** Sem teto, 10⁶ combinações caem por força bruta em minutos. */
  @Test
  void tentativasSeEsgotamEBloqueiamOCodigoCerto() {
    FieldVerificationService service = service(AGORA);
    service.challenge(SUBJECT, TENANT, VerifiableField.PHONE, perfil("11999998888", null));
    String certo = repo.enviado;

    for (int i = 0; i < 3; i++) {
      assertThat(service.confirm(SUBJECT, TENANT, VerifiableField.PHONE, "000000")).isFalse();
    }

    assertThat(service.confirm(SUBJECT, TENANT, VerifiableField.PHONE, certo)).isFalse();
  }

  @Test
  void codigoExpiradoNaoVerifica() {
    service(AGORA).challenge(SUBJECT, TENANT, VerifiableField.PHONE, perfil("11999998888", null));
    String codigo = repo.enviado;

    FieldVerificationService depois = service(AGORA.plus(Duration.ofMinutes(11)));

    assertThat(depois.confirm(SUBJECT, TENANT, VerifiableField.PHONE, codigo)).isFalse();
  }

  /** Reusar o mesmo código deixaria a prova de posse valer para sempre. */
  @Test
  void codigoConsumidoNaoValeDuasVezes() {
    FieldVerificationService service = service(AGORA);
    service.challenge(SUBJECT, TENANT, VerifiableField.PHONE, perfil("11999998888", null));
    String codigo = repo.enviado;

    assertThat(service.confirm(SUBJECT, TENANT, VerifiableField.PHONE, codigo)).isTrue();
    assertThat(service.confirm(SUBJECT, TENANT, VerifiableField.PHONE, codigo)).isFalse();
  }

  /**
   * O truque óbvio: validar um telefone e trocar por outro, mantendo o selo. A verificação é de um
   * valor, não de um campo.
   */
  @Test
  void trocarOTelefoneDerrubaAVerificacao() {
    FieldVerificationService service = service(AGORA);
    service.challenge(SUBJECT, TENANT, VerifiableField.PHONE, perfil("11999998888", null));
    service.confirm(SUBJECT, TENANT, VerifiableField.PHONE, repo.enviado);

    assertThat(service.verifiedFields(SUBJECT, TENANT, perfil("11777776666", null))).isEmpty();
  }

  /** Canal não declarado não tem para onde enviar — e aceitar alvo da requisição seria pior. */
  @Test
  void naoEmiteDesafioParaCanalNaoDeclarado() {
    FieldVerificationService service = service(AGORA);

    assertThatThrownBy(
            () -> service.challenge(SUBJECT, TENANT, VerifiableField.PHONE, perfil(null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("não está declarado");
  }

  @Test
  void nascimentoQueBateComOBureauViraVerificacao() {
    LocalDate nascimento = LocalDate.of(1990, 5, 20);

    service(AGORA)
        .recordBirthDateFromBureau(SUBJECT, TENANT, nascimento, nascimento, "identity-check:1");

    assertThat(service(AGORA).verifiedFields(SUBJECT, TENANT, perfil(null, nascimento)))
        .containsExactly(VerifiableField.BIRTH_DATE);
  }

  /** Divergência não vira verificação — quem decide o desfecho é o gate, não este serviço. */
  @Test
  void nascimentoDivergenteNaoViraVerificacao() {
    service(AGORA)
        .recordBirthDateFromBureau(
            SUBJECT, TENANT, LocalDate.of(1990, 5, 20), LocalDate.of(1991, 5, 20), "x");

    assertThat(repo.verifications).isEmpty();
  }

  /**
   * Mesmo precedente do bureau ({@link #nascimentoQueBateComOBureauViraVerificacao}), mas com
   * {@code method = DOCUMENT}: a fonte independente aqui é a documentoscopia, não o bureau, e a
   * trilha precisa distinguir as duas — são forças de prova diferentes numa contestação.
   */
  @Test
  void nascimentoQueBateComODocumentoViraVerificacao() {
    LocalDate nascimento = LocalDate.of(1990, 5, 20);

    service(AGORA)
        .recordBirthDateFromDocument(SUBJECT, TENANT, nascimento, nascimento, "doc-check:1");

    assertThat(repo.verifications).hasSize(1);
    FieldVerification verificacao = repo.verifications.getFirst();
    assertThat(verificacao.method()).isEqualTo(VerificationMethod.DOCUMENT);
    assertThat(verificacao.evidence()).isEqualTo("doc-check:1");
    assertThat(service(AGORA).verifiedFields(SUBJECT, TENANT, perfil(null, nascimento)))
        .containsExactly(VerifiableField.BIRTH_DATE);
  }

  /** Mesmo motivo do caso do bureau: divergência não vira verificação, nem exceção. */
  @Test
  void nascimentoDivergenteDoDocumentoNaoViraVerificacao() {
    service(AGORA)
        .recordBirthDateFromDocument(
            SUBJECT, TENANT, LocalDate.of(1990, 5, 20), LocalDate.of(1991, 5, 20), "doc-check:1");

    assertThat(repo.verifications).isEmpty();
  }
}
