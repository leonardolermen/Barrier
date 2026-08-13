package com.barrier.riskengine.assurance.service;

import com.barrier.riskengine.assurance.client.BiometricSubmission;
import com.barrier.riskengine.assurance.client.DocumentSubmission;
import com.barrier.riskengine.assurance.client.DocumentVerificationResult;
import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
  private final List<AssuranceRecordedListener> listeners;

  public AssuranceService(
      DocumentVerificationProvider documentProvider,
      BiometricVerificationProvider biometricProvider,
      AssuranceCheckRepository repository,
      List<AssuranceRecordedListener> listeners) {
    this.documentProvider = documentProvider;
    this.biometricProvider = biometricProvider;
    this.repository = repository;
    this.listeners = listeners;
  }

  /**
   * O consentimento entra pela assinatura do serviço, não do provedor: é obrigação legal do
   * tratamento, não parte da verificação técnica do documento. O provedor não sabe — nem precisa
   * saber — que existe consentimento; quem carimba o {@code AssuranceCheck} devolvido é o serviço,
   * imediatamente antes de persistir.
   */
  @Transactional
  public DocumentVerificationResult verifyDocument(
      UUID subjectId, String tenantId, DocumentSubmission submission, AssuranceConsent consent) {
    requireConsent(consent);
    DocumentVerificationResult result = documentProvider.verify(subjectId, tenantId, submission);
    AssuranceCheck persisted = persist(result.check().withConsent(consent));
    return new DocumentVerificationResult(persisted, result.extracted());
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
    scheduleNotification(check);
    return check;
  }

  /**
   * Adia a notificação para depois do commit — não para depois de {@code repository.save}.
   *
   * <p>{@code verifyDocument}/{@code verifyBiometrics} são {@code @Transactional}, e
   * {@code AssuranceReassessmentTrigger.onRecorded} chama {@code AssessmentService.submit}, que
   * também é {@code @Transactional} sem propagation própria — sem isto, os dois juntariam na
   * mesma transação. Uma falha de banco dentro do {@code submit} (ex.: a corrida tratada em
   * {@code SubjectService.create}) marcaria a transação como rollback-only <b>antes</b> de o
   * catch por listener rodar; o catch engoliria a exceção, o método devolveria normalmente, e o
   * commit final estouraria {@code UnexpectedRollbackException} — perdendo a verificação de
   * assurance que acabou de ser gravada, com um log dizendo que ela "segue válida". Notificar só
   * depois do commit garante que o {@code AssuranceCheck} já está persistido antes de qualquer
   * listener rodar.
   *
   * <p><b>Isso sozinho não basta</b> — não abre transação nova para o listener, só adia a
   * chamada. No {@code JpaTransactionManager}, durante o {@code afterCommit} o
   * {@code EntityManagerHolder} da transação que acabou de commitar ainda está ligado à thread
   * (a limpeza só roda depois, em {@code cleanupAfterCompletion}), então um consumidor
   * {@code @Transactional} comum (`REQUIRED`) chamado por um listener <b>entraria</b> nessa
   * mesma transação já commitada em vez de abrir uma própria. Por isso
   * {@code AssuranceReassessmentTrigger.onRecorded} é {@code REQUIRES_NEW} — a responsabilidade
   * de abrir transação própria é de quem escreve depois do commit, este método só garante o
   * "depois".
   *
   * <p>Fora de uma transação de verdade (testes unitários com Mockito, sem contexto Spring) não
   * há sincronização ativa para registrar — notifica na hora, como antes.
   */
  private void scheduleNotification(AssuranceCheck check) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              notifyListeners(check);
            }
          });
    } else {
      notifyListeners(check);
    }
  }

  /**
   * Roda depois de a gravação já estar comitada: o desfecho já está persistido e é isso que
   * sustenta a reavaliação, então uma falha em quem reage não pode desfazer a verificação. O
   * isolamento é <b>por listener</b>, mesmo padrão do {@code WatchlistImporter}: um consumidor que
   * quebra não pode levar os outros junto nem invalidar a gravação.
   */
  private void notifyListeners(AssuranceCheck check) {
    for (AssuranceRecordedListener listener : listeners) {
      try {
        listener.onRecorded(check);
      } catch (RuntimeException e) {
        log.error(
            "Listener {} falhou ao reagir à verificação {} do subject {}; a gravação segue válida",
            listener.getClass().getSimpleName(),
            check.id(),
            check.subjectId(),
            e);
      }
    }
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
