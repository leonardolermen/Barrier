package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Impede aprovação automática de PJ quando o bureau confirmou a empresa mas <b>não trouxe quadro
 * societário</b> — o mesmo fail-open que o {@link ScreeningCoverageRiskRule} fecha para cobertura
 * de lista, reaparecendo um módulo ao lado.
 *
 * <p>O {@code basic_data} da BigBoost (bureau de PJ ligado em {@code application-prod.yml}) não
 * traz QSA. Quando ela atende, {@code CompanyProfile} chega com sócios vazios e, em cadeia,
 * {@link CorporateStructureRiskRule} não dispara (sem sócio não há sócio estrangeiro/PJ a
 * detectar) e o screening de partes relacionadas roda sobre lista vazia — nenhum sócio conferido
 * contra OFAC/CSNU/PEP. Nada disso deixava rastro: a avaliação concluía APROVADO e a trilha
 * afirmava que o KYB tinha rodado.
 *
 * <p><b>Não dá para distinguir, com o dado disponível hoje, "o bureau não fornece QSA" de "a
 * empresa legitimamente não tem sócios"</b> (empresário individual, por exemplo). {@code
 * CompanyProfile} não carrega natureza jurídica nem porte, e nenhum provider de CNPJ (BrasilAPI,
 * BigBoost) expõe esse campo hoje — investigado antes de escrever esta regra, não é omissão.
 * Sem o dado para decidir, a regra é <b>fail-closed</b>: força revisão mesmo sabendo que parte dos
 * casos rebaixados é MEI/empresário individual legítimo, porque a alternativa — aprovar em
 * silêncio quando é limite de fonte — é o risco que este item existe para fechar. Estreitar isso
 * (parar de rebaixar quem legitimamente não tem sócio) é trabalho futuro condicionado a um
 * provedor que informe natureza jurídica/porte.
 *
 * <p><b>Regulatória, não de apetite.</b> A Resolução BCB 44 exige identificar quem controla a
 * pessoa jurídica; o quadro societário é o insumo mínimo para isso, e sem ele o screening de
 * partes relacionadas simplesmente não acontece — não é calibração de sensibilidade, é ausência
 * de controle. Diferente do {@link CorporateStructureRiskRule} (que pontua sinais <i>dentro</i> de
 * um QSA que existe — sócio estrangeiro/PJ — e por isso é apetite, configurável/desligável por
 * tenant), esta regra detecta a <i>ausência</i> do QSA, o mesmo tipo de gap que {@link
 * ScreeningCoverageRiskRule} fecha para listas. Por isso entra em {@code RegulatoryRiskRules} e
 * não pode ser desligada pelo registry.
 *
 * <p>A evidência inclui o bureau que atendeu ({@link IdentityCheck#provider()}), para o analista
 * distinguir limite de fonte (ex.: BigBoost) de empresa sem sócios.
 */
@Component
public class CorporateStructureCoverageRiskRule implements RiskRule {

  private static final String RULE_CODE = "CORPORATE_STRUCTURE_COVERAGE";
  private static final int SCORE = 300;

  @Override
  public RiskResult evaluate(RiskContext context) {
    CompanyProfile company = context.company();
    if (company == null || !company.partners().isEmpty()) {
      // company == null: PF, ou PJ sem confirmação do bureau — já coberto por IdentityRiskRule.
      // partners não vazio: QSA veio, nada a fazer aqui.
      return RiskResult.notApplicable(RULE_CODE);
    }

    IdentityCheck identity = context.identity();
    String provider =
        identity == null || identity.provider() == null ? "desconhecido" : identity.provider();
    return new RiskResult(
        RULE_CODE,
        SCORE,
        Severity.HIGH,
        "PJ confirmada pelo bureau sem quadro societário (QSA) — screening de sócios não pôde "
            + "rodar",
        List.of("bureau:" + provider),
        RiskRecommendation.REVIEW);
  }

  @Override
  public String code() {
    return RULE_CODE;
  }
}
