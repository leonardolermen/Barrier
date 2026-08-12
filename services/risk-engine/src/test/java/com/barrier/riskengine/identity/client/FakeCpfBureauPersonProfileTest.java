package com.barrier.riskengine.identity.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.subject.profile.domain.RegistrationCompleteness;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.domain.SubjectProfilePatch;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * O bureau simulado devolve o cadastro objetivo que um bureau real de PF devolveria.
 *
 * <p>Sem isso, <b>toda</b> avaliação de pessoa física em dev era rebaixada para revisão por
 * cadastro incompleto — o caminho de aprovação automática ficava inalcançável, escondido atrás de
 * um problema de ambiente, e a fila de EDD enchia de casos que não pedem julgamento nenhum.
 */
class FakeCpfBureauPersonProfileTest {

  private final FakeCpfBureauProvider provider = new FakeCpfBureauProvider();

  private static BureauQuery cpf(String digits) {
    return new BureauQuery("CPF", digits, "Fulano de Tal");
  }

  @Test
  void cpfRegularDevolvePerfilCadastral() {
    BureauResult result = provider.check(cpf("11144477735"));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.MATCH);
    assertThat(result.person()).isNotNull();
    assertThat(result.person().birthDate()).isNotNull();
    assertThat(result.person().nationality()).isEqualTo("Brasileira");
    assertThat(result.person().address()).isNotNull();
    assertThat(result.person().address().state()).isEqualTo("SP");
  }

  /**
   * Ocupação continua faltando de propósito: bureau nenhum a fornece, é declaração do cliente. Se o
   * mock a preenchesse, esconderia que o gate ainda depende do parceiro.
   */
  @Test
  void perfilDoBureauSozinhoNaoCompletaOCadastroDePf() {
    SubjectProfile profile = comPerfilDoBureau(provider.check(cpf("11144477735")), null);

    RegistrationCompleteness completeness = RegistrationCompleteness.evaluate("CPF", profile);

    assertThat(completeness.complete()).isFalse();
    assertThat(completeness.missingFields()).containsExactly("ocupação");
  }

  /** Com a ocupação declarada pelo parceiro, o cadastro fecha e a aprovação automática é possível. */
  @Test
  void comOcupacaoDeclaradaOCadastroFica() {
    SubjectProfile profile = comPerfilDoBureau(provider.check(cpf("11144477735")), "Engenheira");

    assertThat(RegistrationCompleteness.evaluate("CPF", profile).complete()).isTrue();
  }

  /** O caminho de cadastro incompleto continua exercitável — senão o mock esconderia o gate. */
  @Test
  void cenarioSemCadastroNaoDevolvePerfil() {
    BureauResult result = provider.check(cpf("99981234567"));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.MATCH);
    assertThat(result.person()).isNull();
  }

  /** Desfecho que não é MATCH não traz cadastro: não há identidade confirmada para cadastrar. */
  @Test
  void desfechoNegativoNaoTrazPerfil() {
    assertThat(provider.check(cpf("99961234567")).person()).isNull();
  }

  /** Aplica o perfil do bureau como o {@code AssessmentProcessor} faz: por patch, sem sobrescrever. */
  private static SubjectProfile comPerfilDoBureau(BureauResult result, String occupation) {
    var person = result.person();
    var a = person.address();
    return new SubjectProfilePatch(
            person.birthDate(),
            null,
            person.nationality(),
            occupation,
            null,
            new SubjectProfile.Address(
                a.street(), a.number(), a.complement(), a.district(), a.city(), a.state(), a.zipCode()),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null)
        .applyTo(SubjectProfile.blank(UUID.randomUUID(), "default"));
  }
}
