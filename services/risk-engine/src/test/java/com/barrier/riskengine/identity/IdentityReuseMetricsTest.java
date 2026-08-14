package com.barrier.riskengine.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.identity.client.BureauProvider;
import com.barrier.riskengine.identity.client.BureauResult;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.repository.interfaces.IdentityCheckRepository;
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
 * Contadores de procedência da verificação de identidade — ver
 * docs/implementation/reuso-de-verificacao-de-identidade.md Task 4.
 *
 * <p>Sem separar reuso de consulta fresca, uma queda de custo é indistinguível de uma queda de
 * tráfego — e uma flag de reuso ligada por engano numa base grande não apareceria em lugar
 * nenhum.
 */
@ExtendWith(MockitoExtension.class)
class IdentityReuseMetricsTest {

  private static final String CPF = "11144477735";
  private static final String NOME = "MARIA SILVA";

  @Mock BureauProvider provider;
  @Mock IdentityCheckRepository repository;

  private IdentityService service(SimpleMeterRegistry registry, boolean reuseEnabled) {
    return new IdentityService(
        List.of(provider), repository, new CircuitBreakerRegistry(5, Duration.ofSeconds(30)),
        reuseEnabled, Duration.ofHours(24), registry);
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
  void contaReusoEConsultaFrescaSeparadamente() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    IdentityService service = service(registry, true);

    when(repository.findReusable(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(verificado("aval-1")));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    service.verify(comando());

    assertThat(registry.counter("barrier.identity.check", "outcome", "reused").count())
        .isEqualTo(1.0);
    assertThat(registry.counter("barrier.identity.check", "outcome", "fresh").count())
        .isEqualTo(0.0);
  }

  @Test
  void contaConsultaFrescaQuandoNaoHaReuso() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    IdentityService service = service(registry, true);

    when(provider.supports("CPF")).thenReturn(true);
    when(provider.check(any())).thenReturn(BureauResult.match("ok"));
    when(provider.name()).thenReturn("bigboost");
    when(repository.findReusable(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.verify(comando());

    assertThat(registry.counter("barrier.identity.check", "outcome", "fresh").count())
        .isEqualTo(1.0);
    assertThat(registry.counter("barrier.identity.check", "outcome", "reused").count())
        .isEqualTo(0.0);
  }

  @Test
  void unavailableNaoContaEmNenhumDosDois() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    IdentityService service = service(registry, true);

    when(provider.supports("CPF")).thenReturn(false);

    service.verify(comando());

    assertThat(registry.counter("barrier.identity.check", "outcome", "fresh").count())
        .isEqualTo(0.0);
    assertThat(registry.counter("barrier.identity.check", "outcome", "reused").count())
        .isEqualTo(0.0);
  }
}
