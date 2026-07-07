package com.barrier.riskengine.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.identity.client.BureauProvider;
import com.barrier.riskengine.identity.client.BureauQuery;
import com.barrier.riskengine.identity.client.BureauResult;
import com.barrier.riskengine.identity.client.BureauUnavailableException;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.repository.IdentityCheckRepository;
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
    return new IdentityService(List.of(provider), repository);
  }

  private VerifyIdentityCommand cpfCommand() {
    return new VerifyIdentityCommand("aid", "CPF", "11144477735", "Fulano");
  }

  @Test
  void matchViraVerified() {
    when(provider.check(any(BureauQuery.class))).thenReturn(BureauResult.match("ok"));
    when(provider.name()).thenReturn("stub");

    IdentityCheck check = service().verify(cpfCommand());

    assertThat(check.status()).isEqualTo(IdentityStatus.VERIFIED);
  }

  @Test
  void notFoundViraNotFound() {
    when(provider.check(any(BureauQuery.class)))
        .thenReturn(new BureauResult(BureauResult.Outcome.NOT_FOUND, "não encontrado"));
    when(provider.name()).thenReturn("stub");

    IdentityCheck check = service().verify(cpfCommand());

    assertThat(check.status()).isEqualTo(IdentityStatus.NOT_FOUND);
    assertThat(check.isRejected()).isTrue();
  }

  @Test
  void bureauIndisponivelViraUnavailable() {
    when(provider.check(any(BureauQuery.class)))
        .thenThrow(new BureauUnavailableException("timeout"));
    when(provider.name()).thenReturn("stub");

    IdentityCheck check = service().verify(cpfCommand());

    assertThat(check.status()).isEqualTo(IdentityStatus.UNAVAILABLE);
    assertThat(check.isRejected()).isFalse();
  }

  @Test
  void semProviderParaTipoViraUnavailable() {
    // provider não suporta o tipo -> nenhum selecionado
    var svc = new IdentityService(List.of(provider), repository);

    IdentityCheck check =
        svc.verify(new VerifyIdentityCommand("aid", "PASSAPORTE", "X", "Fulano"));

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
        new IdentityService(List.of(provider, fallback), repository).verify(cpfCommand());

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
        new IdentityService(List.of(provider, fallback), repository).verify(cpfCommand());

    assertThat(check.status()).isEqualTo(IdentityStatus.UNAVAILABLE);
    assertThat(check.provider()).isEqualTo("todos");
  }

  @Test
  void resultadoDefinitivoNaoTentaOProximo() {
    when(provider.supports("CPF")).thenReturn(true);
    when(fallback.supports("CPF")).thenReturn(true);
    when(provider.check(any(BureauQuery.class)))
        .thenReturn(new BureauResult(BureauResult.Outcome.NOT_FOUND, "não existe"));
    when(provider.name()).thenReturn("primario");

    IdentityCheck check =
        new IdentityService(List.of(provider, fallback), repository).verify(cpfCommand());

    assertThat(check.status()).isEqualTo(IdentityStatus.NOT_FOUND);
    verify(fallback, never()).check(any());
  }
}
