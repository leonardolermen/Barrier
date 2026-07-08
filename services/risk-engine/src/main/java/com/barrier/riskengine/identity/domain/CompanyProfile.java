package com.barrier.riskengine.identity.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Perfil objetivo de uma PJ, extraído do bureau (Receita Federal via BrasilAPI) no momento da
 * verificação de identidade. Não é persistido (somos operador — guardar o mínimo); vive só o
 * suficiente para alimentar as regras de risco do motor.
 *
 * @param openingDate data de início de atividade (pode ser {@code null} se ausente/ilegível)
 * @param cnaeCode CNAE fiscal principal (7 dígitos, como texto); {@code null} se ausente
 * @param cnaeDescription descrição legível do CNAE
 * @param partners quadro societário direto (QSA), quando disponível
 */
public record CompanyProfile(
    LocalDate openingDate, String cnaeCode, String cnaeDescription, List<Partner> partners) {

  public CompanyProfile {
    partners = partners == null ? List.of() : List.copyOf(partners);
  }

  /**
   * Um sócio do quadro societário direto.
   *
   * @param name nome do sócio
   * @param legalEntity {@code true} quando o sócio é PJ (holding/participação encadeada)
   * @param foreign {@code true} quando o sócio é estrangeiro
   * @param qualification qualificação do sócio (texto do bureau), para explicabilidade
   */
  public record Partner(String name, boolean legalEntity, boolean foreign, String qualification) {}
}
