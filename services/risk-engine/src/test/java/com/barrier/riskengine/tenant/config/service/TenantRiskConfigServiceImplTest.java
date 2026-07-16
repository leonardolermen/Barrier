package com.barrier.riskengine.tenant.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.tenant.config.domain.TenantRiskConfigEntry;
import com.barrier.riskengine.tenant.config.repository.TenantRiskConfigRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantRiskConfigServiceImplTest {

  @Mock TenantRiskConfigRepository repository;

  private TenantRiskConfigServiceImpl service() {
    return new TenantRiskConfigServiceImpl(repository);
  }

  @Test
  void semOverrideUsaDefault() {
    when(repository.find("acme", "NEW_COMPANY", "months")).thenReturn(Optional.empty());

    assertThat(service().getInt("acme", "NEW_COMPANY", "months", 6)).isEqualTo(6);
  }

  @Test
  void comOverrideUsaValorDoTenant() {
    when(repository.find("acme", "NEW_COMPANY", "months"))
        .thenReturn(
            Optional.of(
                TenantRiskConfigEntry.create("acme", "NEW_COMPANY", "months", "12", "admin")));

    assertThat(service().getInt("acme", "NEW_COMPANY", "months", 6)).isEqualTo(12);
  }

  @Test
  void semOverrideDeSetUsaDefault() {
    when(repository.find("acme", "SENSITIVE_CNAE", "cnae-codes")).thenReturn(Optional.empty());

    assertThat(service().getStringSet("acme", "SENSITIVE_CNAE", "cnae-codes", Set.of("6619302")))
        .containsExactly("6619302");
  }

  @Test
  void comOverrideDeSetUneComDefault() {
    when(repository.find("acme", "SENSITIVE_CNAE", "cnae-codes"))
        .thenReturn(
            Optional.of(
                TenantRiskConfigEntry.create(
                    "acme", "SENSITIVE_CNAE", "cnae-codes", "1111111,2222222", "admin")));

    assertThat(service().getStringSet("acme", "SENSITIVE_CNAE", "cnae-codes", Set.of("6619302")))
        .containsExactlyInAnyOrder("6619302", "1111111", "2222222");
  }
}
