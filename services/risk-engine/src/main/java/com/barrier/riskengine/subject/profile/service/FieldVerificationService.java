package com.barrier.riskengine.subject.profile.service;

import com.barrier.riskengine.subject.profile.client.interfaces.OtpSender;
import com.barrier.riskengine.subject.profile.domain.FieldVerification;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.domain.VerifiableField;
import com.barrier.riskengine.subject.profile.domain.VerificationChallenge;
import com.barrier.riskengine.subject.profile.domain.VerificationMethod;
import com.barrier.riskengine.subject.profile.repository.interfaces.FieldVerificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verificação de veracidade dos campos do cadastro.
 *
 * <p>Fecha a porta que o gate de completude deixava aberta: preencher com dado plausível e
 * inventado satisfazia o checklist e liberava aprovação automática. Aqui um campo só conta como
 * verificado se houve <b>evento independente</b> confirmando aquele valor — posse do canal (OTP) ou
 * concordância com fonte autoritativa (bureau).
 */
@Service
public class FieldVerificationService {

  private static final Logger log = LoggerFactory.getLogger(FieldVerificationService.class);

  private final FieldVerificationRepository repository;
  private final OtpSender otpSender;
  private final Clock clock;
  private final int maxAttempts;
  private final Duration ttl;

  public FieldVerificationService(
      FieldVerificationRepository repository,
      OtpSender otpSender,
      Clock clock,
      @Value("${barrier.verification.otp.max-attempts:5}") int maxAttempts,
      @Value("${barrier.verification.otp.ttl:PT10M}") Duration ttl) {
    this.repository = repository;
    this.otpSender = otpSender;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
    this.ttl = ttl;
  }

  /**
   * Emite um desafio para o canal <b>já declarado no cadastro</b>.
   *
   * <p>O destino vem do cadastro, nunca da requisição: aceitar um alvo arbitrário transformaria o
   * endpoint em confirmação de qualquer número — o cliente validaria o próprio telefone e
   * declararia outro, e o selo de verificado passaria a atestar nada.
   */
  @Transactional
  public UUID challenge(UUID subjectId, String tenantId, VerifiableField field, SubjectProfile profile) {
    String target =
        switch (field) {
          case PHONE -> profile.phone();
          case EMAIL -> profile.email();
          case BIRTH_DATE, ADDRESS ->
              throw new IllegalArgumentException(
                  "Campo " + field + " não é verificável por OTP; a verificação é contra fonte externa");
        };
    if (target == null || target.isBlank()) {
      throw new IllegalArgumentException("Campo " + field + " não está declarado no cadastro");
    }

    VerificationChallenge.Issued issued =
        VerificationChallenge.issue(
            subjectId, tenantId, field, target, maxAttempts, ttl, clock.instant());
    repository.saveChallenge(issued.challenge());
    otpSender.send(field, target, issued.code());
    return issued.challenge().id();
  }

  /**
   * Confirma um desafio. Devolve {@code true} só quando o código bate, o desafio está no prazo,
   * ainda tem tentativa e o valor no cadastro continua sendo o que foi desafiado.
   *
   * <p>Tentativa errada <b>gasta</b> uma tentativa e é persistida na hora: contar em memória
   * deixaria a força bruta passar entre instâncias.
   */
  @Transactional
  public boolean confirm(UUID subjectId, String tenantId, VerifiableField field, String code) {
    Optional<VerificationChallenge> found =
        repository.findLatestChallenge(subjectId, tenantId, field);
    if (found.isEmpty()) {
      return false;
    }
    VerificationChallenge challenge = found.get();
    Instant now = clock.instant();
    if (!challenge.usable(now)) {
      log.info("Verificação de {} recusada: desafio expirado, consumido ou sem tentativas", field);
      return false;
    }
    if (!challenge.matches(code)) {
      repository.updateChallenge(challenge.failedAttempt());
      return false;
    }
    repository.updateChallenge(challenge.consumed(now));
    repository.save(
        new FieldVerification(
            UUID.randomUUID(),
            subjectId,
            tenantId,
            field,
            VerificationMethod.OTP,
            challenge.target(),
            "challenge:" + challenge.id(),
            now));
    return true;
  }

  /**
   * Registra a concordância entre o nascimento declarado e o que o bureau devolveu.
   *
   * <p>Divergência não vira verificação — e também não vira exceção: quem decide o que fazer com
   * cadastro não verificado é o gate de completude, com o desfecho que ele já tem
   * ({@code SOLICITAR_DOCUMENTO}), não este serviço.
   */
  @Transactional
  public void recordBirthDateFromBureau(
      UUID subjectId, String tenantId, LocalDate declared, LocalDate fromBureau, String evidence) {
    if (declared == null || fromBureau == null || !declared.equals(fromBureau)) {
      return;
    }
    repository.save(
        new FieldVerification(
            UUID.randomUUID(),
            subjectId,
            tenantId,
            VerifiableField.BIRTH_DATE,
            VerificationMethod.BUREAU,
            declared.toString(),
            evidence,
            clock.instant()));
  }

  /**
   * Registra a concordância entre o nascimento declarado e o que a documentoscopia leu do
   * documento apresentado.
   *
   * <p>Mesmo precedente de {@link #recordBirthDateFromBureau}, com {@code method = DOCUMENT}: a
   * fonte independente aqui é a documentoscopia, não o bureau, e a trilha precisa distinguir as
   * duas — são forças de prova diferentes numa contestação. Divergência também não vira exceção
   * aqui; quem decide o que fazer com ela é a regra de risco de documentoscopia, não este
   * serviço.
   */
  @Transactional
  public void recordBirthDateFromDocument(
      UUID subjectId,
      String tenantId,
      LocalDate declared,
      LocalDate fromDocument,
      String evidence) {
    if (declared == null || fromDocument == null || !declared.equals(fromDocument)) {
      return;
    }
    repository.save(
        new FieldVerification(
            UUID.randomUUID(),
            subjectId,
            tenantId,
            VerifiableField.BIRTH_DATE,
            VerificationMethod.DOCUMENT,
            declared.toString(),
            evidence,
            clock.instant()));
  }

  /**
   * Campos verificados <b>e ainda válidos</b> para o cadastro atual.
   *
   * <p>O cruzamento com o valor corrente é o que impede o truque de verificar um telefone e trocar
   * por outro depois: a verificação é de um valor, não de um campo.
   */
  @Transactional(readOnly = true)
  public Set<VerifiableField> verifiedFields(UUID subjectId, String tenantId, SubjectProfile profile) {
    List<FieldVerification> verifications = repository.findBySubjectAndTenant(subjectId, tenantId);
    Set<VerifiableField> valid = EnumSet.noneOf(VerifiableField.class);
    for (FieldVerification v : verifications) {
      if (v.covers(currentValueOf(v.field(), profile))) {
        valid.add(v.field());
      }
    }
    return valid;
  }

  private static String currentValueOf(VerifiableField field, SubjectProfile profile) {
    return switch (field) {
      case PHONE -> profile.phone();
      case EMAIL -> profile.email();
      case BIRTH_DATE -> profile.birthDate() == null ? null : profile.birthDate().toString();
      case ADDRESS -> profile.address() == null ? null : profile.address().zipCode();
    };
  }
}
