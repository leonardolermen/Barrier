package com.barrier.riskengine.subject.profile.domain;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Quais campos do cadastro mudaram <b>de fato</b> e são lidos por alguém que decide.
 *
 * <p><b>Por que a comparação é necessária.</b> O {@code PUT} do cadastro é progressivo e o
 * {@link SubjectProfilePatch#applyTo} mescla: o parceiro reenviar o mesmo endereço é chamada
 * legítima e frequente. Disparar reavaliação por "houve um PUT" transformaria integração
 * idempotente em consulta paga de bureau — e o parceiro que sincroniza cadastro em lote pagaria a
 * base inteira por não ter mudado nada.
 *
 * <p><b>Por que a lista é fechada.</b> Material é o campo que alguma regra lê ou que o
 * {@code RegistrationCompleteness} exige; o resto não muda decisão nenhuma e por isso não justifica
 * o custo. A lista abaixo é o mapa do que hoje é lido:
 *
 * <ul>
 *   <li>{@code birthDate}, {@code nationality} — identidade e cadastro mínimo (CMN 4.753);
 *   <li>{@code address}, {@code phone} — {@code ConsistencyRiskRule} (DDD × UF);
 *   <li>{@code occupation}, {@code declaredIncome} — cadastro mínimo de PF;
 *   <li>{@code cnaeCode} — {@code SensitiveCnaeRiskRule};
 *   <li>{@code foundingDate} — {@code NewCompanyRiskRule};
 *   <li>{@code shareCapital}, {@code legalRepresentative*}, {@code partners} — KYB, e o
 *       representante/QSA ainda vira parte relacionada no screening.
 * </ul>
 *
 * <p>Ficam de fora {@code email} e {@code cnaeDescription}: nenhuma regra os lê, e a descrição do
 * CNAE muda quando a fonte reescreve o rótulo do mesmo código.
 */
public final class MaterialProfileChange {

  private MaterialProfileChange() {}

  /**
   * Campos materiais que este patch efetivamente altera no perfil atual.
   *
   * @return conjunto vazio quando nada material muda — o chamador não deve reavaliar
   */
  public static Set<String> detect(SubjectProfile current, SubjectProfilePatch patch) {
    Set<String> changed = new LinkedHashSet<>();
    compare(changed, "birthDate", patch.birthDate(), current.birthDate());
    compare(changed, "nationality", patch.nationality(), current.nationality());
    compare(changed, "occupation", patch.occupation(), current.occupation());
    compare(changed, "declaredIncome", patch.declaredIncome(), current.declaredIncome());
    compare(changed, "address", patch.address(), current.address());
    compare(changed, "phone", patch.phone(), current.phone());
    compare(changed, "foundingDate", patch.foundingDate(), current.foundingDate());
    compare(changed, "cnaeCode", patch.cnaeCode(), current.cnaeCode());
    compare(changed, "shareCapital", patch.shareCapital(), current.shareCapital());
    compare(
        changed,
        "legalRepresentativeName",
        patch.legalRepresentativeName(),
        current.legalRepresentativeName());
    compare(
        changed,
        "legalRepresentativeDocument",
        patch.legalRepresentativeDocument(),
        current.legalRepresentativeDocument());
    // Lista vazia no patch significa "não informado" (mesma semântica do applyTo), não "zerei o
    // quadro societário" — tratá-la como mudança faria todo PUT de PF reavaliar uma PJ inexistente.
    if (patch.partners() != null
        && !patch.partners().isEmpty()
        && !Objects.equals(patch.partners(), current.partners())) {
      changed.add("partners");
    }
    return changed;
  }

  /** Campo nulo no patch é "não informado" e nunca conta; igual ao atual também não. */
  private static void compare(Set<String> changed, String field, Object candidate, Object existing) {
    if (candidate != null && !Objects.equals(normalize(candidate), normalize(existing))) {
      changed.add(field);
    }
  }

  /**
   * Texto é comparado sem espaço nas pontas e sem diferença de caixa: "SP" e "sp " são o mesmo
   * estado, e tratá-los como mudança faria o parceiro pagar bureau por normalização de formulário.
   */
  private static Object normalize(Object value) {
    if (value instanceof String s) {
      return s.trim().toLowerCase();
    }
    if (value instanceof java.math.BigDecimal d) {
      // 1000 e 1000.00 são o mesmo capital social; compareTo, não equals.
      return d.stripTrailingZeros();
    }
    return value;
  }
}
