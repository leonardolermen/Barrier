package com.barrier.riskengine.subject.profile.service;

import com.barrier.riskengine.subject.profile.client.interfaces.RegistryValidationProvider;
import com.barrier.riskengine.subject.profile.domain.RegistryValidationRequest;
import com.barrier.riskengine.subject.profile.domain.RegistryValidationResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.domain.VerifiableField;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquestra a validação cadastral (Datavalid/Serpro {@code pessoa-fisica/validacao}) e devolve o
 * cadastro atualizado ao chamador — mantém o {@code AssessmentProcessor} sem conhecer o
 * {@link RegistryValidationProvider} nem gravar {@code FieldVerification} diretamente (camadas:
 * service → service é o padrão já usado no módulo, ver CLAUDE.md).
 *
 * <p><b>Chama só quando pode mudar o desfecho</b> — mesmo princípio do ADR-0015 (não queimar
 * consulta paga sem que ela possa alterar a decisão). O Datavalid é cobrado por consulta; o único
 * item do gate de completude que ele sabe fechar é nascimento <i>declarado e não conferido</i>
 * ({@link com.barrier.riskengine.subject.profile.domain.RegistrationCompleteness}: endereço só é
 * cobrado por presença, não por verificação, e ocupação/nacionalidade não têm contrapartida no
 * Datavalid). Por isso o chamador só invoca este serviço quando o gate está prestes a rebaixar a
 * avaliação exatamente por essa causa — ver {@code AssessmentProcessor.complete}.
 */
@Service
public class RegistryValidationService {

  private static final Logger log = LoggerFactory.getLogger(RegistryValidationService.class);

  private final RegistryValidationProvider provider;
  private final FieldVerificationService fieldVerificationService;
  private final boolean enabled;

  public RegistryValidationService(
      RegistryValidationProvider provider,
      FieldVerificationService fieldVerificationService,
      @Value("${barrier.registry-validation.enabled:false}") boolean enabled) {
    this.provider = provider;
    this.fieldVerificationService = fieldVerificationService;
    this.enabled = enabled;
  }

  /**
   * Confere nascimento (e endereço, quando há CNH e o CEP já está declarado) contra RFB/SENATRAN,
   * grava as {@link com.barrier.riskengine.subject.profile.domain.FieldVerification} que o
   * Datavalid confirmar e devolve o conjunto de campos verificados atualizado — para o chamador
   * reavaliar a completude sem outra consulta paga.
   *
   * <p><b>Só PF</b> (RFB/SENATRAN não tem o que confirmar para PJ), <b>só quando a flag está
   * ligada</b>, e <b>só uma chamada</b>: se não há nascimento declarado e pendente de conferência,
   * devolve o conjunto recebido sem chamar o provedor — não há nada que o Datavalid possa mudar.
   * Falha do provedor (indisponível, 429, erro de configuração) e divergência do declarado
   * <b>não</b> gravam verificação; devolvem o conjunto recebido inalterado — fail-closed, a mesma
   * degradação de hoje (cadastro segue não verificado, o gate rebaixa como já rebaixava).
   *
   * @param evidenceRef ponteiro para a prova (id da avaliação) — nunca dado do cadastro
   */
  @Transactional
  public Set<VerifiableField> verifyIfWorthwhile(
      UUID subjectId,
      String tenantId,
      String documentType,
      String documentDigits,
      String declaredName,
      SubjectProfile profile,
      Set<VerifiableField> verifiedFields,
      String evidenceRef) {
    if (!enabled || !"CPF".equals(documentType)) {
      return verifiedFields;
    }
    boolean birthDateCandidate =
        profile.birthDate() != null && !verifiedFields.contains(VerifiableField.BIRTH_DATE);
    if (!birthDateCandidate) {
      // Nada declarado-e-pendente que o Datavalid confirme: a chamada paga não teria como mudar
      // o desfecho do gate (ADR-0015).
      return verifiedFields;
    }
    boolean addressCandidate =
        profile.address() != null
            && !isBlank(profile.address().zipCode())
            && !verifiedFields.contains(VerifiableField.ADDRESS);

    RegistryValidationRequest request =
        buildRequest(documentDigits, declaredName, profile, addressCandidate);

    Optional<RegistryValidationResult> maybeResult =
        provider.validate(subjectId, tenantId, request);
    if (maybeResult.isEmpty()) {
      log.info("Validação cadastral indisponível para o subject {}; cadastro segue não conferido", subjectId);
      return verifiedFields;
    }
    RegistryValidationResult result = maybeResult.get();

    Set<VerifiableField> updated = EnumSet.noneOf(VerifiableField.class);
    updated.addAll(verifiedFields);

    boolean birthConfirmed =
        result.rfbExiste() && result.rfb() != null && Boolean.TRUE.equals(result.rfb().dataNascimento());
    fieldVerificationService.recordBirthDateFromRegistry(
        subjectId, tenantId, profile.birthDate(), birthConfirmed, "registry-validation:" + evidenceRef);
    if (birthConfirmed) {
      updated.add(VerifiableField.BIRTH_DATE);
    }

    if (addressCandidate && result.cnhExiste() && result.cnh() != null && result.cnh().endereco() != null) {
      boolean addressConfirmed =
          Boolean.TRUE.equals(result.cnh().endereco().cep()) && Boolean.TRUE.equals(result.cnh().endereco().uf());
      fieldVerificationService.recordAddressFromRegistry(
          subjectId,
          tenantId,
          profile.address().zipCode(),
          addressConfirmed,
          "registry-validation:" + evidenceRef);
      if (addressConfirmed) {
        updated.add(VerifiableField.ADDRESS);
      }
    }
    return updated;
  }

  private static RegistryValidationRequest buildRequest(
      String documentDigits, String declaredName, SubjectProfile profile, boolean addressCandidate) {
    RegistryValidationRequest.Endereco endereco =
        addressCandidate
            ? new RegistryValidationRequest.Endereco(
                profile.address().street(),
                profile.address().number(),
                profile.address().complement(),
                profile.address().district(),
                profile.address().zipCode(),
                profile.address().city(),
                profile.address().state())
            : null;
    return new RegistryValidationRequest(
        documentDigits,
        declaredName,
        profile.birthDate(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        endereco,
        null,
        null);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
