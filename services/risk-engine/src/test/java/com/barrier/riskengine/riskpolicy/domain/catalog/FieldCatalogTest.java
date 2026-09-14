package com.barrier.riskengine.riskpolicy.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldCatalogTest {

  private static final Instant QUANDO = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void resolve_campo_de_topo_a_partir_do_contexto() {
    CompanyProfile empresa =
        new CompanyProfile(LocalDate.of(2026, 4, 1), "6499-9/99", "Outras atividades", List.of());
    RiskContext ctx = new RiskContext("a-1", "t-1", null, null, empresa, null, null, QUANDO);

    PolicyField campo = FieldCatalog.V1.find("company.openingDate").orElseThrow();

    assertThat(campo.resolve(ctx)).isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(campo.type()).isEqualTo(PolicyFieldType.DATE);
    assertThat(campo.input()).isEqualTo(ContextInput.COMPANY);
  }

  @Test
  void resolve_campo_de_elemento_a_partir_do_socio() {
    CompanyProfile.Partner socio = new CompanyProfile.Partner("ACME BV", true, true, "Sócio");

    PolicyField campo = FieldCatalog.V1.find("company.partners[].foreign").orElseThrow();

    assertThat(campo.resolve(socio)).isEqualTo(true);
    assertThat(campo.parentListId()).isEqualTo("company.partners");
  }

  @Test
  void campo_ausente_resolve_para_nulo_sem_estourar() {
    RiskContext semEmpresa = new RiskContext("a-1", "t-1", null, null, null, null, null, QUANDO);

    assertThat(FieldCatalog.V1.find("company.openingDate").orElseThrow().resolve(semEmpresa))
        .isNull();
  }

  @Test
  void campo_lista_resolve_para_lista_vazia_em_vez_de_nulo_quando_insumo_ausente() {
    RiskContext semInsumo = new RiskContext("a-1", "t-1", null, null, null, null, null, QUANDO);

    assertThat(FieldCatalog.V1.find("company.partners").orElseThrow().resolve(semInsumo))
        .isEqualTo(List.of());
    assertThat(FieldCatalog.V1.find("screening.hits").orElseThrow().resolve(semInsumo))
        .isEqualTo(List.of());
  }

  @Test
  void campos_de_pii_direto_nao_estao_no_catalogo() {
    List<String> proibidos =
        List.of(
            "identity.documentDigits",
            "identity.name",
            "identity.rawResponse",
            "screening.hits[].matchedName",
            "company.partners[].name",
            "profile.phone",
            "profile.email",
            "profile.address.street",
            "profile.legalRepresentativeDocument");

    assertThat(proibidos).allSatisfy(id -> assertThat(FieldCatalog.V1.find(id)).isEmpty());
  }

  @Test
  void campos_sensiveis_presentes_sao_marcados_para_nao_vazar_valor() {
    assertThat(FieldCatalog.V1.find("profile.birthDate").orElseThrow().exposure())
        .isEqualTo(EvidenceExposure.OUTCOME_ONLY);
    assertThat(FieldCatalog.V1.find("profile.declaredIncome").orElseThrow().exposure())
        .isEqualTo(EvidenceExposure.OUTCOME_ONLY);
  }

  @Test
  void todo_campo_de_elemento_aponta_para_uma_lista_que_existe_no_catalogo() {
    assertThat(FieldCatalog.V1.all())
        .filteredOn(campo -> campo.parentListId() != null)
        .allSatisfy(
            campo -> {
              PolicyField lista = FieldCatalog.V1.find(campo.parentListId()).orElseThrow();
              assertThat(lista.type()).isEqualTo(PolicyFieldType.LIST);
            });
  }
}
