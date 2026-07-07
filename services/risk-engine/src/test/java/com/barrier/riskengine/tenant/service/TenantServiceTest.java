package com.barrier.riskengine.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.tenant.domain.Tenant;
import com.barrier.riskengine.tenant.domain.UnknownTenantException;
import com.barrier.riskengine.tenant.repository.TenantRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

  @Mock TenantRepository repository;

  @Test
  void resolveTenantAtivo() {
    when(repository.findById("acme")).thenReturn(Optional.of(new Tenant("acme", "ACME", true)));

    assertThat(new TenantService(repository).resolve("acme").id()).isEqualTo("acme");
  }

  @Test
  void headerAusenteFalha() {
    assertThatThrownBy(() -> new TenantService(repository).resolve(" "))
        .isInstanceOf(UnknownTenantException.class);
  }

  @Test
  void tenantDesconhecidoFalha() {
    when(repository.findById("x")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> new TenantService(repository).resolve("x"))
        .isInstanceOf(UnknownTenantException.class);
  }

  @Test
  void tenantInativoFalha() {
    when(repository.findById("old")).thenReturn(Optional.of(new Tenant("old", "Antigo", false)));

    assertThatThrownBy(() -> new TenantService(repository).resolve("old"))
        .isInstanceOf(UnknownTenantException.class);
  }
}
