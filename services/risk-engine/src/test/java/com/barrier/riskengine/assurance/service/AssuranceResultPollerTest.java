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
 */
class AssuranceResultPollerTest {

  private static final UUID SUBJECT = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final AssuranceCheckRepository repository = mock(AssuranceCheckRepository.class);
  private final BiometricVerificationProvider provider = mock(BiometricVerificationProvider.class);
  private final AssuranceService assuranceService = mock(AssuranceService.class);

  private final AssuranceResultPoller poller =
      new AssuranceResultPoller(
          repository, provider, assuranceService, transactionTemplate(), CLOCK, Duration.ofMinutes(1));

  private AssuranceCheck pending(Instant pinExpiresAt) {
    return AssuranceCheck.pendingWithPin(
        UUID.randomUUID(), SUBJECT, "tenant-1", "datavalid-serpro", "hash", NOW.minusSeconds(30),
        "123456", pinExpiresAt);
  }

  @Test
  void semItensReivindicadosNaoChamaOProvider() {
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class))).thenReturn(List.of());

    int resolved = poller.poll();

    assertThat(resolved).isZero();
    org.mockito.Mockito.verifyNoInteractions(provider);
  }

  /** Cidadão ainda não completou a captura, PIN dentro da validade: nada é gravado. */
  @Test
  void aindaSemResultadoENaoExpiradoNaoGravaNada() {
    AssuranceCheck check = pending(NOW.plusSeconds(60));
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class))).thenReturn(List.of(check));
    when(provider.pollResult(check)).thenReturn(Optional.empty());

    int resolved = poller.poll();

    assertThat(resolved).isZero();
    verify(assuranceService, never()).recordPolledResult(any());
  }

  /** Resultado presente: grava via AssuranceService, que dispara a mesma trilha de qualquer check. */
  @Test
  void resultadoPresenteGravaComoDesfechoFinal() {
    AssuranceCheck check = pending(NOW.plusSeconds(60));
    AssuranceCheck resolvido = resolvido(AssuranceOutcome.PASS);
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class))).thenReturn(List.of(check));
    when(provider.pollResult(check)).thenReturn(Optional.of(resolvido));

    int resolved = poller.poll();

    assertThat(resolved).isEqualTo(1);
    verify(assuranceService).recordPolledResult(resolvido);
  }

  /** PIN vencido sem resposta: o poller mesmo marca UNAVAILABLE, não fica tentando para sempre. */
  @Test
  void pinExpiradoSemRespostaViraIndisponivel() {
    AssuranceCheck check = pending(NOW.minusSeconds(1)); // já expirado
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class))).thenReturn(List.of(check));
    when(provider.pollResult(check)).thenReturn(Optional.empty());

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
    when(repository.claimPendingBiometric(anyInt(), any(Duration.class)))
        .thenReturn(List.of(falha, ok));
    when(provider.pollResult(falha)).thenThrow(new RuntimeException("timeout"));
    when(provider.pollResult(ok)).thenReturn(Optional.of(resolvidoOk));

    int resolved = poller.poll();

    assertThat(resolved).isEqualTo(1);
    verify(assuranceService).recordPolledResult(resolvidoOk);
  }

  private AssuranceCheck resolvido(AssuranceOutcome outcome) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        SUBJECT,
        "tenant-1",
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
