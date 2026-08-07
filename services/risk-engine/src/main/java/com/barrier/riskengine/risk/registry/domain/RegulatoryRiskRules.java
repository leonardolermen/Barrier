package com.barrier.riskengine.risk.registry.domain;

import java.util.Set;

/**
 * Famílias de regra que existem por exigência regulatória (PLD-FT) e por isso <b>não podem ser
 * desligadas nem ter vigência limitada</b> pelo registry — nem por engano, nem por decisão
 * operacional, nem por quem tiver acesso à API de administração.
 *
 * <p>É o mesmo conjunto que o ArchUnit ({@code regras_fixas_nao_dependem_de_config_por_tenant})
 * já protege contra virar configurável por parceiro. O registry tinha o buraco simétrico: a regra
 * não podia ser <i>calibrada</i> por tenant, mas podia ser <i>desligada</i> para todo mundo com um
 * único {@code PUT} — e o motor então aprovava um apontamento de sanção com score zero.
 *
 * <p>O registry continua sendo kill switch legítimo para regras de <b>apetite de risco</b>
 * ({@code NEW_COMPANY}, {@code SENSITIVE_CNAE}, {@code PHONE_ADDRESS_MISMATCH},
 * {@code CORPORATE_STRUCTURE}) — que é o caso de uso real: desarmar uma regra que começou a gerar
 * falso positivo em massa, sem esperar deploy.
 */
public final class RegulatoryRiskRules {

  private static final Set<String> CODES =
      Set.of("IDENTITY", "SANCTION", "PEP", "NEGATIVE_MEDIA", "SCREENING_COVERAGE");

  private RegulatoryRiskRules() {}

  /** Indica se a família de regra é regulatória e, portanto, sempre ativa. */
  public static boolean isRegulatory(String ruleCode) {
    return ruleCode != null && CODES.contains(ruleCode);
  }

  /** Códigos protegidos, para mensagens de erro e para a resposta da API de gestão. */
  public static Set<String> codes() {
    return CODES;
  }
}
