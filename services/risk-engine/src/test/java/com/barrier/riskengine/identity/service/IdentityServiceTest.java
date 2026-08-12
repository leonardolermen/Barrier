package com.barrier.riskengine.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.identity.client.BureauProvider;
import com.barrier.riskengine.identity.client.BureauQuery;
import com.barrier.riskengine.identity.client.BureauResult;
import com.barrier.riskengine.identity.client.BureauUnavailableException;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.repository.interfaces.IdentityCheckRepository;
import com.barrier.riskengine.resilience.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityServiceTest {

  @Mock BureauProvider provider;
  @Mock BureauProvider fallback;
  @Mock IdentityCheckRepository repository;

  @BeforeEach
  void savePassesThrough() {
    when(repository.save(any(IdentityCheck.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  private IdentityService service() {
    when(provider.supports("CPF")).thenReturn(true);
    return new IdentityService(List.of(provider), repository, breakers());
  }

  /** Registro novo a cada serviço: o estado do disjuntor não deve vazar entre os testes. */
  private static CircuitBreakerRegistry breakers() {
    return new CircuitBreakerRegistry(3, Duration.ofSeconds(30));
  }

  private VerifyIdentityCommand cpfCommand() {
    return new VerifyIdentityCommand("aid", "CPF", "11144477735", "Fulano");
  }

  @Test
  void matchViraVerified() {
    when(provider.check(any(BureauQuery.class))).thenReturn(BureauResult.match("ok"));
    when(provider.name()).thenReturn("stub");

    IdentityCheck check = service().verify(cpfCommand()).check();

    assertThat(check.status()).isEqualTo(IdentityStatus.VERIFIED);
  }

  @Test
  void notFoundViraNotFound() {
    when(provider.check(any(BureauQuery.class)))
        .thenReturn(new BureauResult(BureauResult.Outcome.NOT_FOUND, "não encontrado"));
    when(provider.name()).thenReturn("stub");

    IdentityCheck check = service().verify(cpfCommand()).check();

    assertThat(check.status()).isEqualTo(IdentityStatus.NOT_FOUND);
    assertThat(check.isRejected()).isTrue();
  }

  @Test
  void bureauIndisponivelViraUnavailable() {
    when(provider.check(any(BureauQuery.class)))
        .thenThrow(new BureauUnavailableException("timeout"));
    when(provider.name()).thenReturn("stub");

    IdentityCheck check = service().verify(cpfCommand()).check();

    assertThat(check.status()).isEqualTo(IdentityStatus.UNAVAILABLE);
    assertThat(check.isRejected()).isFalse();
  }

  @Test
  void semProviderParaTipoViraUnavailable() {
    // provider não suporta o tipo -> nenhum selecionado
    var svc = new IdentityService(List.of(provider), repository, breakers());

    IdentityCheck check =
        svc.verify(new VerifyIdentityCommand("aid", "PASSAPORTE", "X", "Fulano")).check();

    assertThat(check.status()).isEqualTo(IdentityStatus.UNAVAILABLE);
    assertThat(check.provider()).isEqualTo("nenhum");
  }

  @Test
  void primarioIndisponivelCaiParaOProximo() {
    when(provider.supports("CPF")).thenReturn(true);
    when(fallback.supports("CPF")).thenReturn(true);
    when(provider.check(any(BureauQuery.class)))
        .thenThrow(new BureauUnavailableException("timeout"));
    when(provider.name()).thenReturn("primario");
    when(fallback.check(any(BureauQuery.class))).thenReturn(BureauResult.match("ok"));
    when(fallback.name()).thenReturn("secundario");

    IdentityCheck check =
        new IdentityService(List.of(provider, fallback), repository, breakers()).verify(cpfCommand()).check();

    assertThat(check.status()).isEqualTo(IdentityStatus.VERIFIED);
    assertThat(check.provider()).isEqualTo("secundario");
  }

  @Test
  void todosIndisponiveisResultaUnavailable() {
    when(provider.supports("CPF")).thenReturn(true);
    when(fallback.supports("CPF")).thenReturn(true);
    when(provider.check(any(BureauQuery.class)))
        .thenThrow(new BureauUnavailableException("t1"));
    when(fallback.check(any(BureauQuery.class)))
        .thenThrow(new BureauUnavailableException("t2"));
    when(provider.name()).thenReturn("p1");
    when(fallback.name()).thenReturn("p2");

    IdentityCheck check =
        new IdentityService(List.of(provider, fallback), repository, breakers()).verify(cpfCommand()).check();

    assertThat(check.status()).isEqualTo(IdentityStatus.UNAVAILABLE);
    assertThat(check.provider()).isEqualTo("todos");
  }

  /**
   * Regressão: o stub ({@code authoritative() == false}) não pode ser fallback de um bureau real
   * indisponível — isso convertia indisponibilidade em identidade verificada.
   */
  @Test
  void bureauRealIndisponivelNaoCaiParaOStub() {
    when(provider.supports("CPF")).thenReturn(true);
    when(provider.authoritative()).thenReturn(true); // bureau real
    when(fallback.supports("CPF")).thenReturn(true); // mock devolve authoritative() == false
    when(provider.check(any(BureauQuery.class))).thenThrow(new BureauUnavailableException("timeout"));
    when(provider.name()).thenReturn("bureau-real");

    IdentityCheck check =
        new IdentityService(List.of(provider, fallback), repository, breakers()).verify(cpfCommand()).check();

    assertThat(check.status()).isEqualTo(IdentityStatus.UNAVAILABLE);
    verify(fallback, never()).check(any());
  }

  /** Sem nenhum bureau real para o tipo, o stub segue valendo — é o que sustenta dev/teste. */
  @Test
  void semBureauRealOStubContinuaNaCadeia() {
    when(provider.supports("CPF")).thenReturn(true);
    when(provider.authoritative()).thenReturn(false);
    when(provider.check(any(BureauQuery.class))).thenReturn(BureauResult.match("stub"));
    when(provider.name()).thenReturn("stub");

    IdentityCheck check =
        new IdentityService(List.of(provider), repository, breakers()).verify(cpfCommand()).check();

    assertThat(check.status()).isEqualTo(IdentityStatus.VERIFIED);
  }

  /**
   * O disjuntor é o que impede um provider degradado de cobrar o timeout inteiro de cada avaliação:
   * passado o limite de falhas seguidas, ele deixa de ser chamado e a avaliação vai direto para
   * UNAVAILABLE (que a IdentityRiskRule converte em revisão humana).
   */
  @Test
  void bureauEmFalhaParaDeSerChamadoEAAvaliacaoVaiParaUnavailable() {
    when(provider.supports("CPF")).thenReturn(true);
    when(provider.check(any(BureauQuery.class))).thenThrow(new BureauUnavailableException("timeout"));
    when(provider.name()).thenReturn("bureau-real");
    IdentityService svc = new IdentityService(List.of(provider), repository, breakers());

    for (int i = 0; i < 3; i++) {
      assertThat(svc.verify(cpfCommand()).check().status()).isEqualTo(IdentityStatus.UNAVAILABLE);
    }
    verify(provider, times(3)).check(any());

    IdentityCheck depoisDeAbrir = svc.verify(cpfCommand()).check();

    assertThat(depoisDeAbrir.status()).isEqualTo(IdentityStatus.UNAVAILABLE);
    // nenhuma chamada nova: o disjuntor recusou antes de sair para a rede
    verify(provider, times(3)).check(any());
  }

  /** Disjuntor aberto no primário não impede o secundário saudável de atender. */
  @Test
  void disjuntorAbertoNoPrimarioAindaCaiParaOProximo() {
    when(provider.supports("CPF")).thenReturn(true);
    when(fallback.supports("CPF")).thenReturn(true);
    when(provider.check(any(BureauQuery.class))).thenThrow(new BureauUnavailableException("timeout"));
    when(provider.name()).thenReturn("primario");
    when(fallback.name()).thenReturn("secundario");
    when(fallback.check(any(BureauQuery.class))).thenReturn(BureauResult.match("ok"));
    IdentityService svc = new IdentityService(List.of(provider, fallback), repository, breakers());

    for (int i = 0; i < 3; i++) {
      svc.verify(cpfCommand());
    }
    IdentityCheck check = svc.verify(cpfCommand()).check();

    assertThat(check.status()).isEqualTo(IdentityStatus.VERIFIED);
    assertThat(check.provider()).isEqualTo("secundario");
    verify(provider, times(3)).check(any());
  }

  @Test
  void resultadoDefinitivoNaoTentaOProximo() {
    when(provider.supports("CPF")).thenReturn(true);
    when(fallback.supports("CPF")).thenReturn(true);
    when(provider.check(any(BureauQuery.class)))
        .thenReturn(new BureauResult(BureauResult.Outcome.NOT_FOUND, "não existe"));
    when(provider.name()).thenReturn("primario");

    IdentityCheck check =
        new IdentityService(List.of(provider, fallback), repository, breakers()).verify(cpfCommand()).check();

    assertThat(check.status()).isEqualTo(IdentityStatus.NOT_FOUND);
    verify(fallback, never()).check(any());
  }
}
