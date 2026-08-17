package com.barrier.riskengine.subject.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A materialidade é o <b>único</b> freio de custo do gatilho de cadastro: {@code PROFILE_PATCH}
 * fura o intervalo mínimo (ADR-0019), então cada alteração material vira uma avaliação — e uma
 * consulta paga de bureau. Estes testes protegem principalmente o "não mudou".
 */
class MaterialProfileChangeTest {

  @Test
  void reenviar_os_mesmos_valores_nao_e_alteracao() {
    SubjectProfile atual = perfil();

    var mudou =
        MaterialProfileChange.detect(
            atual, patch(b -> b.phone("11988887777").occupation("Analista")));

    assertThat(mudou).isEmpty();
  }

  @Test
  void campo_nulo_no_patch_nunca_conta() {
    assertThat(MaterialProfileChange.detect(perfil(), patch(b -> b))).isEmpty();
  }

  @Test
  void mudanca_de_telefone_e_material() {
    var mudou = MaterialProfileChange.detect(perfil(), patch(b -> b.phone("21999990000")));

    assertThat(mudou).containsExactly("phone");
  }

  /** Normalização de formulário não pode custar bureau. */
  @Test
  void diferenca_so_de_caixa_ou_espaco_nao_e_alteracao() {
    var mudou = MaterialProfileChange.detect(perfil(), patch(b -> b.occupation("  analista ")));

    assertThat(mudou).isEmpty();
  }

  /** 1000 e 1000.00 são o mesmo capital social. */
  @Test
  void escala_decimal_diferente_nao_e_alteracao() {
    var mudou =
        MaterialProfileChange.detect(
            perfil(), patch(b -> b.declaredIncome(new BigDecimal("5000.00"))));

    assertThat(mudou).isEmpty();
  }

  @Test
  void email_nao_e_material() {
    var mudou = MaterialProfileChange.detect(perfil(), patch(b -> b.email("outro@exemplo.com")));

    assertThat(mudou).isEmpty();
  }

  /** Lista vazia é "não informado", igual a campo nulo — não é "zerei o quadro societário". */
  @Test
  void partners_vazio_no_patch_nao_e_alteracao() {
    var mudou = MaterialProfileChange.detect(perfil(), patch(b -> b.partners(List.of())));

    assertThat(mudou).isEmpty();
  }

  @Test
  void varios_campos_materiais_de_uma_vez() {
    var mudou =
        MaterialProfileChange.detect(
            perfil(),
            patch(b -> b.phone("21999990000").nationality("PT").birthDate(LocalDate.of(1990, 5, 3))));

    assertThat(mudou).containsExactlyInAnyOrder("phone", "nationality", "birthDate");
  }

  // --- fixtures --------------------------------------------------------------

  private static SubjectProfile perfil() {
    return new SubjectProfile(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "acme",
        LocalDate.of(1985, 1, 20),
        null,
        "BR",
        "Analista",
        new BigDecimal("5000"),
        new SubjectProfile.Address("Rua A", "10", null, "Centro", "São Paulo", "SP", "01000000"),
        "11988887777",
        "cliente@exemplo.com",
        null,
        null,
        null,
        null,
        null,
        List.of(),
        Instant.now(),
        Instant.now());
  }

  private static SubjectProfilePatch patch(java.util.function.UnaryOperator<PatchBuilder> f) {
    return f.apply(new PatchBuilder()).build();
  }

  /** Só para deixar cada teste declarar apenas o campo que lhe interessa. */
  private static final class PatchBuilder {
    private LocalDate birthDate;
    private String nationality;
    private String occupation;
    private BigDecimal declaredIncome;
    private String phone;
    private String email;
    private List<SubjectProfile.Partner> partners;

    PatchBuilder birthDate(LocalDate v) {
      this.birthDate = v;
      return this;
    }

    PatchBuilder nationality(String v) {
      this.nationality = v;
      return this;
    }

    PatchBuilder occupation(String v) {
      this.occupation = v;
      return this;
    }

    PatchBuilder declaredIncome(BigDecimal v) {
      this.declaredIncome = v;
      return this;
    }

    PatchBuilder phone(String v) {
      this.phone = v;
      return this;
    }

    PatchBuilder email(String v) {
      this.email = v;
      return this;
    }

    PatchBuilder partners(List<SubjectProfile.Partner> v) {
      this.partners = v;
      return this;
    }

    SubjectProfilePatch build() {
      return new SubjectProfilePatch(
          birthDate,
          null,
          nationality,
          occupation,
          declaredIncome,
          null,
          phone,
          email,
          null,
          null,
          null,
          null,
          null,
          partners);
    }
  }
}
