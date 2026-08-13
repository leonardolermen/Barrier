package com.barrier.riskengine.assurance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code AssuranceResultPoller} no mesmo formato do {@code OutboxRelay}: reivindica, consulta o
 * provedor fora de transação, grava. Aqui a transação é fake (mesmo padrão de
 * {@code AssessmentProcessorTest}) — o que interessa é a orquestração, não o gerenciador real.
 *
 * <p>O CPF passado ao provider vem de {@code SubjectService.findById}, nunca de estado guardado
 * pelo provider entre a emissão do PIN e o poll — é exatamente o que corrige o defeito da
 * primeira versão (mapa em memória, que só funcionava numa única réplica). Todo teste aqui
 * resolve o subject do zero a cada poll, provando que o dado vem do banco.
 */
class AssuranceResultPollerTest {

  private static final UUID SUBJECT = UUID.randomUUID();
  private static final String TENANT = "tenant-1";
  private static final String DOCUMENT = "11144477735";
  private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final AssuranceCheckRepository repository = mock(AssuranceCheckRepository.class);
  private final BiometricVerificationProvider provider = mock(BiometricVerificationProvider.class);
  private final AssuranceService assuranceService = mock(AssuranceService.class);
  private final SubjectService subjects = mock(SubjectService.class);

  private final AssuranceResultPoller poller =
      new AssuranceResultPoller(
          repository,
          provider,
          assuranceService,
          subjects,
          transactionTemplate(),
          CLOCK,
          Duration.ofMinutes(1));

  private AssuranceCheck pending(Instant pinExpiresAt) {
    return AssuranceCheck.pendingWithPin(
        UUID.randomUUID(), SUBJECT, TENANT, "datavalid-serpro", "hash", NOW.minusSeconds(30),
        "123456789", pinExpiresAt);
  }

  private void stubSubject() {
    when(subjects.findById(SUBJECT, TENANT))
        .thenReturn(new Subject(SUBJECT, "CPF", DOCUMENT, "Fulano de Tal", Instant.now()));
  }

  @Test
  void semItensReivindicadosNaoChamaOProvider() {
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class))).thenReturn(List.of());

    int resolved = poller.poll();

    assertThat(resolved).isZero();
    org.mockito.Mockito.verifyNoInteractions(provider);
    org.mockito.Mockito.verifyNoInteractions(subjects);
  }

  /** Cidadão ainda não completou a captura, PIN dentro da validade: nada é gravado. */
  @Test
  void aindaSemResultadoENaoExpiradoNaoGravaNada() {
    AssuranceCheck check = pending(NOW.plusSeconds(60));
    stubSubject();
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class))).thenReturn(List.of(check));
    when(provider.pollResult(check, DOCUMENT)).thenReturn(Optional.empty());

    int resolved = poller.poll();

    assertThat(resolved).isZero();
    verify(assuranceService, never()).recordPolledResult(any());
  }

  /** Resultado presente: grava via AssuranceService, que dispara a mesma trilha de qualquer check. */
  @Test
  void resultadoPresenteGravaComoDesfechoFinal() {
    AssuranceCheck check = pending(NOW.plusSeconds(60));
    AssuranceCheck resolvido = resolvido(AssuranceOutcome.PASS);
    stubSubject();
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class))).thenReturn(List.of(check));
    when(provider.pollResult(check, DOCUMENT)).thenReturn(Optional.of(resolvido));

    int resolved = poller.poll();

    assertThat(resolved).isEqualTo(1);
    verify(assuranceService).recordPolledResult(resolvido);
  }

  /**
   * <b>O teste que fecha o defeito do mapa em memória</b>: o CPF vem só do
   * {@code SubjectService}, resolvido do zero neste poll — nada foi emitido nem cacheado por
   * este processo antes. Simula exatamente o cenário de produção que quebrava: a réplica que
   * poleia nunca é a que emitiu o PIN. Se a resolução do documento voltar a depender de estado
   * em memória do provider (em vez de vir do poller via {@code SubjectService}), este teste some
   * do jeito errado — ele não teria como detectar isso sozinho, mas prova que o poller nunca
   * pergunta ao provider "de onde veio o CPF": ele resolve e entrega.
   */
  @Test
  void resolveODocumentoDoBancoSemTerPassadoPelaEmissaoDoPinNesteProcesso() {
    AssuranceCheck check = pending(NOW.plusSeconds(60)); // pin só existe porque veio do banco
    AssuranceCheck resolvido = resolvido(AssuranceOutcome.PASS);
    stubSubject();
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class))).thenReturn(List.of(check));
    when(provider.pollResult(check, DOCUMENT)).thenReturn(Optional.of(resolvido));

    poller.poll();

    verify(subjects).findById(SUBJECT, TENANT);
    verify(provider).pollResult(check, DOCUMENT);
  }

  /** PIN vencido sem resposta: o poller mesmo marca UNAVAILABLE, não fica tentando para sempre. */
  @Test
  void pinExpiradoSemRespostaViraIndisponivel() {
    AssuranceCheck check = pending(NOW.minusSeconds(1)); // já expirado
    stubSubject();
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class))).thenReturn(List.of(check));
    when(provider.pollResult(check, DOCUMENT)).thenReturn(Optional.empty());

    int resolved = poller.poll();

    assertThat(resolved).isEqualTo(1);
    org.mockito.ArgumentCaptor<AssuranceCheck> captor =
        org.mockito.ArgumentCaptor.forClass(AssuranceCheck.class);
    verify(assuranceService).recordPolledResult(captor.capture());
    assertThat(captor.getValue().outcome()).isEqualTo(AssuranceOutcome.UNAVAILABLE);
    assertThat(captor.getValue().subjectId()).isEqualTo(SUBJECT);
  }

  /** Um item falhando ao consultar não pode impedir os outros do mesmo lote. */
  @Test
  void falhaConsultandoUmItemNaoImpedeOsDemais() {
    AssuranceCheck falha = pending(NOW.plusSeconds(60));
    AssuranceCheck ok = pending(NOW.plusSeconds(60));
    AssuranceCheck resolvidoOk = resolvido(AssuranceOutcome.FAIL);
    stubSubject();
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class)))
        .thenReturn(List.of(falha, ok));
    when(provider.pollResult(falha, DOCUMENT)).thenThrow(new RuntimeException("timeout"));
    when(provider.pollResult(ok, DOCUMENT)).thenReturn(Optional.of(resolvidoOk));

    int resolved = poller.poll();

    assertThat(resolved).isEqualTo(1);
    verify(assuranceService).recordPolledResult(resolvidoOk);
  }

  /**
   * Falha ao resolver o subject (ex.: banco fora) também não pode travar o resto do lote. Os dois
   * checks precisam de {@code subjectId} <b>distintos</b> — com o mesmo, o segundo {@code when}
   * chamaria o mock já configurado para lançar (mesmos argumentos do primeiro stub) durante a
   * própria configuração do teste, antes de {@code poller.poll()} rodar.
   */
  @Test
  void falhaResolvendoOSubjectNaoImpedeOsDemais() {
    UUID subjectComFalha = UUID.randomUUID();
    UUID subjectOk = UUID.randomUUID();
    AssuranceCheck falha =
        AssuranceCheck.pendingWithPin(
            UUID.randomUUID(), subjectComFalha, TENANT, "datavalid-serpro", "hash",
            NOW.minusSeconds(30), "123456789", NOW.plusSeconds(60));
    AssuranceCheck ok =
        AssuranceCheck.pendingWithPin(
            UUID.randomUUID(), subjectOk, TENANT, "datavalid-serpro", "hash",
            NOW.minusSeconds(30), "987654321", NOW.plusSeconds(60));
    AssuranceCheck resolvidoOk = resolvido(AssuranceOutcome.PASS);
    when(subjects.findById(subjectComFalha, TENANT)).thenThrow(new RuntimeException("banco fora"));
    when(subjects.findById(subjectOk, TENANT))
        .thenReturn(new Subject(subjectOk, "CPF", DOCUMENT, "Fulano de Tal", Instant.now()));
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class)))
        .thenReturn(List.of(falha, ok));
    when(provider.pollResult(ok, DOCUMENT)).thenReturn(Optional.of(resolvidoOk));

    int resolved = poller.poll();

    assertThat(resolved).isEqualTo(1);
    verify(assuranceService).recordPolledResult(resolvidoOk);
  }

  private AssuranceCheck resolvido(AssuranceOutcome outcome) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        SUBJECT,
        TENANT,
        com.barrier.riskengine.assurance.domain.AssuranceKind.BIOMETRIC,
        outcome,
        outcome == AssuranceOutcome.PASS ? 97 : null,
        "datavalid-serpro",
        null,
        "datavalid/v5",
        "hash",
        "detalhe",
        java.util.Set.of(),
        NOW,
        null);
  }

  private static TransactionTemplate transactionTemplate() {
    return new TransactionTemplate(
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {}

          @Override
          public void rollback(TransactionStatus status) {}
        });
  }
}
