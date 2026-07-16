package com.barrier.riskengine.tenant.config.validation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Allowlist e ranges dos parâmetros de risco configuráveis por tenant. Regras regulatórias
 * fixas (bandas de score, PEP, sanção, identidade) não aparecem aqui de propósito — não têm
 * {@code rule_code} conhecido por este validador, então qualquer tentativa de configurá-las é
 * rejeitada.
 *
 * <p>Os defaults abaixo espelham os mesmos {@code @Value} usados nas regras
 * ({@link com.barrier.riskengine.risk.rule.NewCompanyRiskRule},
 * {@link com.barrier.riskengine.risk.rule.SensitiveCnaeRiskRule}) — servem só para a resposta de
 * {@code GET} mostrar o efetivo quando o tenant não tem override; a leitura real usada na
 * avaliação de risco continua vindo de {@code TenantRiskConfigService}.
 */
@Component
public class TenantRiskConfigValidator {

  private static final Pattern CNAE_LIST =
      Pattern.compile("^\\d{7}(,\\d{7})*$");

  private final Map<String, RuleSpec> rules = new LinkedHashMap<>();

  public TenantRiskConfigValidator(
      @Value("${barrier.risk.new-company.months:6}") String newCompanyMonthsDefault,
      @Value("${barrier.risk.new-company.score:150}") String newCompanyScoreDefault,
      @Value("${barrier.risk.sensitive-cnae-score:200}") String sensitiveCnaeScoreDefault) {
    rules.put(
        "NEW_COMPANY",
        new RuleSpec(
            Map.of(
                "months", new ParamSpec(newCompanyMonthsDefault, 1, 24, null),
                "score", new ParamSpec(newCompanyScoreDefault, 1, 500, null))));
    rules.put(
        "SENSITIVE_CNAE",
        new RuleSpec(
            Map.of(
                "score", new ParamSpec(sensitiveCnaeScoreDefault, 1, 500, null),
                "cnae-codes", new ParamSpec("", null, null, CNAE_LIST))));
  }

  /** Lança {@link IllegalArgumentException} (400) se a regra/parâmetro/valor não forem válidos. */
  public void validate(String ruleCode, String paramKey, String paramValue) {
    RuleSpec rule = rules.get(ruleCode);
    if (rule == null) {
      throw new IllegalArgumentException(
          "rule_code '" + ruleCode + "' não é configurável por tenant");
    }
    ParamSpec param = rule.params().get(paramKey);
    if (param == null) {
      throw new IllegalArgumentException(
          "param_key '" + paramKey + "' não é configurável para a regra " + ruleCode);
    }
    param.validate(ruleCode, paramKey, paramValue);
  }

  /** Regras configuráveis por tenant conhecidas por este validador. */
  public java.util.Set<String> ruleCodes() {
    return java.util.Set.copyOf(rules.keySet());
  }

  /** Defaults conhecidos por regra, para a resposta de {@code GET} mostrar o efetivo. */
  public Map<String, String> defaultsOf(String ruleCode) {
    RuleSpec rule = rules.get(ruleCode);
    if (rule == null) {
      return Map.of();
    }
    Map<String, String> defaults = new LinkedHashMap<>();
    rule.params().forEach((key, spec) -> defaults.put(key, spec.defaultValue()));
    return defaults;
  }

  private record RuleSpec(Map<String, ParamSpec> params) {}

  private record ParamSpec(String defaultValue, Integer min, Integer max, Pattern pattern) {

    void validate(String ruleCode, String paramKey, String value) {
      if (pattern != null) {
        if (!pattern.matcher(value).matches()) {
          throw new IllegalArgumentException(
              paramKey + " de " + ruleCode + " deve ser uma lista de CNAEs de 7 dígitos (CSV)");
        }
        return;
      }
      int parsed;
      try {
        parsed = Integer.parseInt(value);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(paramKey + " de " + ruleCode + " deve ser numérico");
      }
      if ((min != null && parsed < min) || (max != null && parsed > max)) {
        throw new IllegalArgumentException(
            paramKey + " de " + ruleCode + " deve estar entre " + min + " e " + max);
      }
    }
  }
}
