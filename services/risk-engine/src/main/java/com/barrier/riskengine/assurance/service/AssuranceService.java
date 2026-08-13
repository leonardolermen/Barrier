package com.barrier.riskengine.assurance.service;

import com.barrier.riskengine.assurance.client.BiometricSubmission;
import com.barrier.riskengine.assurance.client.DocumentSubmission;
import com.barrier.riskengine.assurance.client.DocumentVerificationResult;
import com.barrier.riskengine.assurance.client.ExtractedDocumentFields;
import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import com.barrier.commons.name.NameSimilarity;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.DivergentField;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.service.FieldVerificationService;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import com.barrier.riskengine.subject.service.SubjectService;
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
  private final SubjectProfileService subjectProfileService;
  private final SubjectService subjectService;
  private final FieldVerificationService fieldVerificationService;
  private final boolean enabled;
  private final double nameMatchThreshold;

  public AssuranceService(
      DocumentVerificationProvider documentProvider,
      BiometricVerificationProvider biometricProvider,
      AssuranceCheckRepository repository,
      List<AssuranceRecordedListener> listeners,
      SubjectProfileService subjectProfileService,
      SubjectService subjectService,
      FieldVerificationService fieldVerificationService,
      // Kill switch, mesmo padrão de barrier.rescreening.enabled: desligado, o endpoint recusa
      // (409, IllegalStateException) e nenhum AssuranceCheck nasce — logo nenhuma reavaliação é
      // disparada, porque não há o que notificar. Nasce LIGADO: a etapa já está em uso.
      @Value("${barrier.assurance.enabled:true}") boolean enabled,
      // Mesmo mecanismo dos bureaus (BrasilApiBureauProvider/BigBoostBureauProvider): comparação
      // de nome por similaridade de token, não igualdade de string — ver #diverges.
      @Value("${barrier.assurance.name-match.threshold:0.85}") double nameMatchThreshold) {
    this.documentProvider = documentProvider;
    this.biometricProvider = biometricProvider;
    this.repository = repository;
    this.listeners = listeners;
    this.subjectProfileService = subjectProfileService;
    this.subjectService = subjectService;
    this.fieldVerificationService = fieldVerificationService;
    this.enabled = enabled;
    this.nameMatchThreshold = nameMatchThreshold;
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
    requireEnabled();
    requireConsent(consent);
    DocumentVerificationResult result = documentProvider.verify(subjectId, tenantId, submission);
    AssuranceCheck check = result.check().withConsent(consent);
    ExtractedDocumentFields extracted = result.extracted();
    // extracted só existe quando o desfecho é PASS (ver Javadoc de DocumentVerificationResult),
    // mas o guard fica explícito aqui: sem ele, um provedor futuro que devolva extracted junto
    // de um FAIL somaria failScore + divergenceScore pelo mesmo evento — o mesmo documento
    // adulterado pontuando duas vezes.
    if (extracted != null && check.passed()) {
      check = reconcileWithCadastro(subjectId, tenantId, extracted, check);
    }
    AssuranceCheck persisted = persist(check);
    return new DocumentVerificationResult(persisted, extracted);
  }

  /**
   * Compara o que a documentoscopia leu contra o que o cadastro (CMN 4.753) e o {@code Subject}
   * declaram — nunca escreve os campos extraídos no {@code SubjectProfile}.
   *
   * <p>Nascimento que confere é evento independente confirmando o valor declarado, então vira
   * {@link FieldVerificationService#recordBirthDateFromDocument}, o mesmo padrão de
   * {@code recordBirthDateFromBureau} já usado para o bureau. Nascimento que <b>diverge</b> vira
   * {@link AssuranceCheck#divergences()} para {@code IdentityAssuranceRiskRule} pontuar — é sinal
   * de possível fraude, não campo faltando. Nome é diferente: pertence ao {@code Subject}, não ao
   * cadastro (CMN 4.753), então não tem campo verificável equivalente <b>para ele</b> — divergir
   * também vira {@code divergences()}, mas nunca {@code FieldVerification}. Nenhum dos dois casos
   * carrega o valor declarado nem o extraído — só quais campos divergiram.
   *
   * <p><b>Documento não entra nesta comparação.</b> {@code ExtractedDocumentFields.document} é o
   * número do documento apresentado como o provedor o leu (RG, CNH...), não o CPF/CNPJ que
   * identifica o {@code Subject} (ADR-0011) — são grandezas diferentes, e compará-las geraria
   * divergência sistemática (todo RG "diverge" do CPF do cadastro). Ver {@link DivergentField}.
   */
  private AssuranceCheck reconcileWithCadastro(
      UUID subjectId, String tenantId, ExtractedDocumentFields extracted, AssuranceCheck check) {
    SubjectProfile profile = subjectProfileService.find(subjectId, tenantId);
    Subject subject = subjectService.findById(subjectId, tenantId);

    Set<DivergentField> divergences = EnumSet.noneOf(DivergentField.class);
    if (extracted.birthDate() != null && profile.birthDate() != null) {
      if (extracted.birthDate().equals(profile.birthDate())) {
        fieldVerificationService.recordBirthDateFromDocument(
            subjectId,
            tenantId,
            profile.birthDate(),
            extracted.birthDate(),
            check.providerReference());
      } else {
        divergences.add(DivergentField.BIRTH_DATE);
      }
    }
    if (diverges(subject.name(), extracted.name())) {
      divergences.add(DivergentField.NAME);
    }
    return divergences.isEmpty() ? check : check.withDivergences(divergences);
  }

  /**
   * Similaridade por token ({@code NameSimilarity}), não igualdade de string — o mesmo mecanismo
   * que {@code BrasilApiBureauProvider}/{@code BigBoostBureauProvider} usam para comparar nome
   * declarado contra fonte independente, e pelo mesmo motivo: OCR real abrevia nome do meio, corta
   * sobrenome ou perde acento ("MARIA S SILVA" × "MARIA SILVA"), e igualdade exata transformaria
   * cada uma dessas variações em falso positivo sistemático — cliente legítimo caindo em
   * {@code EM_REVISAO} porque a documentoscopia leu o nome de um jeito ligeiramente diferente do
   * cadastro. Nascimento continua comparação exata: data é data, não string livre sujeita a OCR.
   *
   * <p>Simétrica ({@link NameSimilarity#matchesEitherWay}), não direcional: diferente do bureau
   * (onde o lado oficial é sempre a fonte externa), aqui tanto o nome declarado pelo titular no
   * cadastro quanto o lido do documento podem ser o mais completo dos dois — OCR corta sobrenome
   * (documento fica menor) ou abrevia nome do meio (documento fica igual, cadastro que veio maior
   * de outra fonte), então exigir uma direção fixa deixaria passar metade dos casos reais.
   *
   * <p>{@code isBlank}, não só {@code null}: um provedor que devolva nome em branco (OCR
   * ilegível, mas ainda assim {@code outcome = PASS}) não pode virar sinal de fraude.
   * {@code NameTokens.of("")} produz conjunto vazio, e {@code coveredBy} de conjunto vazio é
   * sempre {@code false} nos dois sentidos — sob a igualdade exata anterior isso não acontecia
   * ({@code "".equals("")} é verdadeiro), então este guard é novo, não redundante.
   */
  private boolean diverges(String declared, String extracted) {
    if (declared == null || extracted == null || declared.isBlank() || extracted.isBlank()) {
      return false;
    }
    return !NameSimilarity.matchesEitherWay(declared, extracted, nameMatchThreshold);
  }

  /** Ver {@link #verifyDocument}: mesmo motivo para o consentimento entrar aqui. */
  @Transactional
  public AssuranceCheck verifyBiometrics(
      UUID subjectId, String tenantId, BiometricSubmission submission, AssuranceConsent consent) {
    requireEnabled();
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
  /**
   * Kill switch de {@code barrier.assurance.enabled}. 409, não 500 nem 404: é uma recusa
   * deliberada de operação, o mesmo tratamento que {@code ProblemExceptionHandler} já dá a
   * {@code IllegalStateException}. Roda antes de {@code requireConsent} — desligado, nem a
   * validação de consentimento importa.
   */
  private void requireEnabled() {
    if (!enabled) {
      throw new IllegalStateException(
          "verificação de documentoscopia/biometria está desabilitada"
              + " (barrier.assurance.enabled=false)");
    }
  }

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
