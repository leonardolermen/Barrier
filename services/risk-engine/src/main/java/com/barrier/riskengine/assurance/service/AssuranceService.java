package com.barrier.riskengine.assurance.service;

import com.barrier.riskengine.assurance.client.BiometricSubmission;
import com.barrier.riskengine.assurance.client.DocumentSubmission;
import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Documentoscopia e biometria: prova de que quem está do outro lado é o titular do documento.
 *
 * <p>É o que o motor não tinha. Ele confirmava que o CPF é regular e que o nome bate com o do
 * bureau — nada disso distingue o titular de alguém que sabe o CPF dele.
 *
 * <p>Guarda o resultado, não a imagem (ADR-0016). O serviço nunca vê foto: recebe a referência de
 * um upload feito direto do dispositivo para o provedor.
 */
@Service
public class AssuranceService {

  private static final Logger log = LoggerFactory.getLogger(AssuranceService.class);

  private final DocumentVerificationProvider documentProvider;
  private final BiometricVerificationProvider biometricProvider;
  private final AssuranceCheckRepository repository;

  public AssuranceService(
      DocumentVerificationProvider documentProvider,
      BiometricVerificationProvider biometricProvider,
      AssuranceCheckRepository repository) {
    this.documentProvider = documentProvider;
    this.biometricProvider = biometricProvider;
    this.repository = repository;
  }

  /**
   * O consentimento entra pela assinatura do serviço, não do provedor: é obrigação legal do
   * tratamento, não parte da verificação técnica do documento. O provedor não sabe — nem precisa
   * saber — que existe consentimento; quem carimba o {@code AssuranceCheck} devolvido é o serviço,
   * imediatamente antes de persistir.
   */
  @Transactional
  public AssuranceCheck verifyDocument(
      UUID subjectId, String tenantId, DocumentSubmission submission, AssuranceConsent consent) {
    requireConsent(consent);
    AssuranceCheck check = documentProvider.verify(subjectId, tenantId, submission);
    return persist(check.withConsent(consent));
  }

  /** Ver {@link #verifyDocument}: mesmo motivo para o consentimento entrar aqui. */
  @Transactional
  public AssuranceCheck verifyBiometrics(
      UUID subjectId, String tenantId, BiometricSubmission submission, AssuranceConsent consent) {
    requireConsent(consent);
    AssuranceCheck check = biometricProvider.verify(subjectId, tenantId, submission);
    return persist(check.withConsent(consent));
  }

  /**
   * Consentimento ausente é tão inválido quanto um consentimento sem finalidade, e tem de recusar
   * do mesmo jeito — mensagem clara, antes de acionar o provedor — em vez de estourar
   * {@code NullPointerException} no meio de {@code consent.validate()}. Quem chama esta assinatura
   * é o controller (Task 5): lá, "esqueceram de mandar o consentimento" é o caminho normal de um
   * cliente mal-implementado, não uma situação excepcional de programação.
   */
  private void requireConsent(AssuranceConsent consent) {
    if (consent == null) {
      throw new IllegalArgumentException("consentimento é obrigatório para esta verificação");
    }
    consent.validate();
  }

  /**
   * Toda tentativa é gravada, inclusive a que falhou.
   *
   * <p>Guardar só a última esconderia o padrão que mais interessa a PLD-FT: cinco tentativas de
   * prova de vida até uma passar não é a mesma coisa que uma tentativa que passou, e a diferença
   * entre as duas é o sinal de fraude.
   */
  private AssuranceCheck persist(AssuranceCheck check) {
    repository.save(check);
    log.info(
        "Verificação {} do subject {}: {} (score {}, provedor {} ref {})",
        check.kind(),
        check.subjectId(),
        check.outcome(),
        check.score(),
        check.provider(),
        check.providerReference());
    return check;
  }

  @Transactional(readOnly = true)
  public Optional<AssuranceCheck> latest(UUID subjectId, String tenantId, AssuranceKind kind) {
    return repository.findLatest(subjectId, tenantId, kind);
  }

  /** Quantas tentativas houve daquele tipo — insumo do sinal de risco, não trivialidade. */
  @Transactional(readOnly = true)
  public long attempts(UUID subjectId, String tenantId, AssuranceKind kind) {
    return repository.findAll(subjectId, tenantId).stream().filter(c -> c.kind() == kind).count();
  }
}
