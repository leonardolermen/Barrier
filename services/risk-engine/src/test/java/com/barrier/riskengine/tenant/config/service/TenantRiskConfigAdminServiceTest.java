package com.barrier.riskengine.tenant.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.tenant.config.domain.TenantRiskConfigEntry;
import com.barrier.riskengine.tenant.config.repository.TenantRiskConfigRepository;
import com.barrier.riskengine.tenant.config.validation.TenantRiskConfigValidator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A validação da allowlist mora <b>dentro</b> deste serviço, não na borda web: enquanto ela
 * ficou no controller, qualquer chamador novo do repositório escrevia override sem passar pela
 * allowlist — e afrouxar controle de risco de um parceiro é exatamente o que ela evita.
 */
@ExtendWith(MockitoExtension.class)
class TenantRiskConfigAdminServiceTest {

  @Mock TenantRiskConfigRepository repository;

  TenantRiskConfigValidator validator;
  TenantRiskConfigAdminService service;

  @BeforeEach
  void setUp() {
    validator = new TenantRiskConfigValidator("6", "150", "200");
    service = new TenantRiskConfigAdminService(repository, validator);
  }

  @Test
  void grava_override_de_parametro_permitido() {
    TenantRiskConfigEntry entry =
        TenantRiskConfigEntry.create("tenant-1", "NEW_COMPANY", "months", "12", "admin");
    when(repository.upsert("tenant-1", "NEW_COMPANY", "months", "12", "admin")).thenReturn(entry);

    TenantRiskConfigEntry saved =
        service.upsert("tenant-1", "NEW_COMPANY", "months", "12", "admin");

    assertThat(saved).isEqualTo(entry);
  }

  @Test
  void recusa_regra_regulatoria_fixa_e_nao_toca_no_repositorio() {
    assertThatThrownBy(() -> service.upsert("tenant-1", "SANCTION", "score", "10", "admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SANCTION");

    verify(repository, never()).upsert(anyString(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void recusa_valor_fora_do_range_e_nao_toca_no_repositorio() {
    assertThatThrownBy(() -> service.upsert("tenant-1", "NEW_COMPANY", "months", "99", "admin"))
        .isInstanceOf(IllegalArgumentException.class);

    verify(repository, never()).upsert(anyString(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void lista_os_overrides_do_tenant() {
    TenantRiskConfigEntry entry =
        TenantRiskConfigEntry.create("tenant-1", "NEW_COMPANY", "months", "12", "admin");
    when(repository.findByTenant("tenant-1")).thenReturn(List.of(entry));

    assertThat(service.findByTenant("tenant-1")).containsExactly(entry);
  }
}
