package com.barrier.riskengine.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.identity.client.BureauProvider;
import com.barrier.riskengine.identity.client.BureauResult;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.repository.interfaces.IdentityCheckRepository;
import com.barrier.riskengine.identity.service.IdentityResult;
import com.barrier.riskengine.identity.service.IdentityService;
import com.barrier.riskengine.identity.service.VerifyIdentityCommand;
import com.barrier.riskengine.resilience.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * `IdentityService` consultando reuso antes de sair para a rede (V040).
 */
@ExtendWith(MockitoExtension.class)
class IdentityServiceReuseTest {

  private static final String CPF = "11144477735";
  private static final String NOME = "MARIA SILVA";

  @Mock BureauProvider provider;
  @Mock IdentityCheckRepository repository;

  private IdentityService service(boolean reuseEnabled) {
    return new IdentityService(
        List.of(provider), repository, new CircuitBreakerRegistry(5, Duration.ofSeconds(30)),
        reuseEnabled, Duration.ofHours(24), new SimpleMeterRegistry());
  }

  private static VerifyIdentityCommand comando() {
    return new VerifyIdentityCommand("aval-2", "tenant-a", "CPF", CPF, NOME);
  }

  private static IdentityCheck verificado(String assessmentId) {
    return IdentityCheck.create(
        assessmentId, "tenant-a", "CPF", CPF, NOME,
        IdentityStatus.VERIFIED, "bigboost", "titular regular", "query-99", "{}");
  }

  @Test
  void reaproveitaCheckRecenteSemChamarOBureau() {
    IdentityCheck anterior = verificado("aval-1");
    when(repository.findReusable(eq("tenant-a"), eq("CPF"), eq(CPF), eq(NOME), any()))
        .thenReturn(Optional.of(anterior));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    IdentityResult resultado = service(true).verify(comando());

    verify(provider, never()).check(any());
    assertThat(resultado.check().status()).isEqualTo(IdentityStatus.VERIFIED);
    assertThat(resultado.check().reusedFromId()).isEqualTo(anterior.id());
  }

  @Test
  void perfilNaoAcompanhaOReuso() {
    when(repository.findReusable(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(verificado("aval-1")));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    IdentityResult resultado = service(true).verify(comando());

    assertThat(resultado.company()).isNull();
    assertThat(resultado.person()).isNull();
  }

  @Test
  void desligadoVaiAoBureauMesmoComCheckRecente() {
    when(provider.supports("CPF")).thenReturn(true);
    when(provider.check(any())).thenReturn(BureauResult.match("ok"));
    when(provider.name()).thenReturn("bigboost");
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service(false).verify(comando());

    verify(provider).check(any());
    verify(repository, never()).findReusable(any(), any(), any(), any(), any());
  }

  @Test
  void cnpjNuncaReaproveita() {
    when(provider.supports("CNPJ")).thenReturn(true);
    when(provider.check(any())).thenReturn(BureauResult.match("ok"));
    when(provider.name()).thenReturn("bigboost");
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service(true)
        .verify(new VerifyIdentityCommand("aval-2", "tenant-a", "CNPJ", "11222333000181", NOME));

    verify(provider).check(any());
    verify(repository, never()).findReusable(any(), any(), any(), any(), any());
  }
}
