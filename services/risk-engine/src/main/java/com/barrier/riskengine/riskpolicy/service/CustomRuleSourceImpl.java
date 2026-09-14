package com.barrier.riskengine.riskpolicy.service;

import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.CustomRuleSource;
import com.barrier.riskengine.risk.rule.interfaces.CustomRules;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicy;
import com.barrier.riskengine.riskpolicy.repository.interfaces.RiskPolicyRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Única implementação de {@link CustomRuleSource}: lê a política {@code ACTIVE} do tenant e adapta
 * cada regra dela para o vocabulário do motor via {@link CustomPolicyRiskRule}.
 *
 * <p><b>Sem cache local, de propósito.</b> Guardar a política ativa num mapa de instância seria
 * estado do cluster na memória de um pod: ativação feita numa réplica não invalidaria o cache das
 * outras quatro, e elas seguiriam decidindo com a política anterior — para uma decisão regulada,
 * silenciosamente. Este projeto já pagou exatamente este erro três vezes num único workstream
 * ({@code WatchlistImportStatus}, o dedup do {@code AlertEvaluator}, os tópicos do {@code
 * KafkaTopicsConfig}), e nas três havia comentário explicando por que o estado local estava
 * certo. Não estava. A leitura de {@link RiskPolicyRepository#findActive} é uma busca indexada por
 * (tenant, domínio, status), uma vez por avaliação; se isto algum dia virar gargalo medido, a
 * saída é cache com invalidação compartilhada entre réplicas, nunca um mapa local.
 *
 * <p>⚠️ Bean único de propósito: {@code RiskScoringService} injeta {@code
 * Optional<CustomRuleSource>}, que resolve para vazio com zero beans e para este com exatamente
 * um — mas um segundo {@code CustomRuleSource} sem {@code @Primary}/{@code @Qualifier} quebra a
 * subida do contexto com {@code NoUniqueBeanDefinitionException}, a mesma classe de falha que
 * custou 107 erros de teste na Task 4 deste plano.
 */
@Service
public class CustomRuleSourceImpl implements CustomRuleSource {

  private final RiskPolicyRepository repository;
  private final ConditionEvaluator evaluator;

  public CustomRuleSourceImpl(RiskPolicyRepository repository, ConditionEvaluator evaluator) {
    this.repository = repository;
    this.evaluator = evaluator;
  }

  @Override
  public CustomRules forContext(RiskContext context) {
    Optional<RiskPolicy> active =
        repository.findActive(context.tenantId(), PolicyDomain.ONBOARDING);
    if (active.isEmpty()) {
      return CustomRules.NONE;
    }
    RiskPolicy policy = active.get();
    List<RiskRule> rules =
        policy.rules().stream()
            .<RiskRule>map(rule -> new CustomPolicyRiskRule(rule, evaluator))
            .toList();
    return new CustomRules(policy.version(), rules);
  }
}
