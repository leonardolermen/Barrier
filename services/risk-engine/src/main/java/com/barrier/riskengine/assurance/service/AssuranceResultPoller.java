package com.barrier.riskengine.assurance.service;

import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Traz o desfecho de verificações biométricas assíncronas (Datavalid/Serpro: PIN emitido, cidadão
 * ainda vai capturar a selfie no app gov.br).
 *
 * <p><b>Mesma forma do {@code OutboxRelay}/{@code AssessmentProcessor}, e pelo mesmo motivo</b>:
 *
 * <ol>
 *   <li><b>reivindica</b> um lote em transação curta ({@code claimPendingBiometric}, {@code FOR
 *       UPDATE SKIP LOCKED} + lease);
 *   <li><b>consulta o provedor</b> para cada item <b>fora</b> de transação — é I/O externo, e
 *       segurar o lock durante ele é o anti-padrão que os dois componentes acima já documentam
 *       como causa de incidente;
 *   <li><b>grava o desfecho</b> de cada um em sua própria transação, via {@code
 *       AssuranceService.recordPolledResult} — o que já dispara a mesma trilha e o mesmo gatilho
 *       de reavaliação que qualquer outro check gravado.
 * </ol>
 *
 * <p>Um check cujo PIN expirou sem resposta vira {@code UNAVAILABLE}, não fica {@code PENDING}
 * para sempre: o cidadão nunca completou a captura, e travar a avaliação indefinidamente por isso
 * seria pior que reconhecer a indisponibilidade e deixar {@code IdentityAssuranceRiskRule} tratá-la
 * como já trata qualquer outro provedor fora do ar.
 *
 * <p><b>Repolar é grátis enquanto o cidadão não age.</b> Sondagem ao vivo do Serpro confirmou que
 * só {@code HTTP 200} e {@code 422/DV001} são cobrados — {@code 422/DV171} (o caso comum
 * enquanto a captura não foi feita) não é. Contraintuitivo o bastante para registrar aqui:
 * {@code barrier.assurance.poller.delay-ms} pode ficar razoavelmente frequente sem preocupação de
 * custo por chamada; não "otimizar" o intervalo pensando em economizar dinheiro que não está
 * sendo gasto.
 *
 * <p><b>Resolve o CPF aqui, não no provider.</b> {@code BiometricVerificationProvider.pollResult}
 * exige o documento já resolvido — nunca resolve sozinho — porque um provider em {@code client}
 * não pode depender do módulo {@code subject} (integração externa só por interface). Este poller
 * já depende de {@code SubjectService}, então é aqui que mora: {@code
 * subjects.findById(pending.subjectId(), pending.tenantId())}, nunca só por {@code subjectId} —
 * o tipo do método é a defesa contra vazar subject de outro tenant, mesmo padrão do {@code
 * AssuranceReassessmentTrigger}. Resolver do banco a cada poll (em vez de cachear em memória) é
 * deliberado: um mapa em memória preenchido na emissão do PIN foi exatamente o defeito que este
 * desenho corrige — só funcionava quando a mesma instância emitia o PIN e poleiava, e este serviço
 * roda replicado por desenho.
 */
@Component
public class AssuranceResultPoller {

  private static final Logger log = LoggerFactory.getLogger(AssuranceResultPoller.class);
  private static final int BATCH = 50;

  private final AssuranceCheckRepository repository;
  private final BiometricVerificationProvider biometricProvider;
  private final AssuranceService assuranceService;
  private final SubjectService subjects;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;
  private final Duration lease;

  public AssuranceResultPoller(
      AssuranceCheckRepository repository,
      BiometricVerificationProvider biometricProvider,
      AssuranceService assuranceService,
      SubjectService subjects,
      TransactionTemplate transactionTemplate,
      Clock clock,
      @Value("${barrier.assurance.poller.lease:PT1M}") Duration lease) {
    this.repository = repository;
    this.biometricProvider = biometricProvider;
    this.assuranceService = assuranceService;
    this.subjects = subjects;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
    this.lease = lease;
  }

  /** Executado periodicamente. Também pode ser chamado direto (ex.: em testes). */
  @Scheduled(fixedDelayString = "${barrier.assurance.poller.delay-ms:5000}")
  public int poll() {
    List<AssuranceCheck> claimed =
        transactionTemplate.execute(status -> repository.claimPendingBiometric(BATCH, lease));
    if (claimed == null || claimed.isEmpty()) {
      return 0;
    }

    int resolved = 0;
    for (AssuranceCheck pending : claimed) {
      if (pollOne(pending)) {
        resolved++;
      }
    }
    return resolved;
  }

  /**
   * Um item falha sozinho: uma exceção consultando o terceiro do lote não pode impedir a gravação
   * dos dois primeiros, que já vieram prontos do provedor.
   */
  private boolean pollOne(AssuranceCheck pending) {
    Optional<AssuranceCheck> outcome;
    try {
      String document = subjects.findById(pending.subjectId(), pending.tenantId()).document();
      outcome = biometricProvider.pollResult(pending, document);
    } catch (RuntimeException e) {
      // Falha ao consultar não é o mesmo que "provedor disse que ainda não saiu" — o item
      // simplesmente libera a posse (claimed_at vencerá) e tenta de novo no próximo ciclo, sem
      // marcar UNAVAILABLE prematuramente por um erro transitório de rede.
      log.warn(
          "Falha ao consultar resultado biométrico do check {}; será tentado de novo",
          pending.id(),
          e);
      return false;
    }

    if (outcome.isPresent()) {
      transactionTemplate.executeWithoutResult(
          status -> assuranceService.recordPolledResult(outcome.get()));
      log.info(
          "Resultado biométrico resolvido para o subject {}: {}",
          pending.subjectId(),
          outcome.get().outcome());
      return true;
    }

    if (pending.expired(clock.instant())) {
      AssuranceCheck expired = expiredOutcome(pending);
      transactionTemplate.executeWithoutResult(
          status -> assuranceService.recordPolledResult(expired));
      log.info(
          "PIN biométrico expirado sem resposta para o subject {} (check {})",
          pending.subjectId(),
          pending.id());
      return true;
    }
    // Ainda pendente, PIN dentro da validade: nada a gravar, tenta de novo depois do lease.
    return false;
  }

  private AssuranceCheck expiredOutcome(AssuranceCheck pending) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        pending.subjectId(),
        pending.tenantId(),
        pending.kind(),
        AssuranceOutcome.UNAVAILABLE,
        null,
        pending.provider(),
        pending.providerReference(),
        pending.algorithmVersion(),
        pending.submittedHash(),
        "PIN expirado sem o cidadão completar a captura",
        pending.divergences(),
        Instant.now(clock),
        pending.consent());
  }
}
