package com.barrier.riskengine.risk.rule.context;

/**
 * Insumo do {@link RiskContext} que uma regra consome — declarado por ela em
 * {@code RiskRule.requires()}.
 *
 * <p>Existe por causa do <b>replay de decisão</b>. Nem todo campo do {@code RiskContext} é
 * reconstruível a partir do que está gravado: {@link #IDENTITY} e {@link #SCREENING} são (a V028
 * guarda o id exato de cada um), {@link #COMPANY} não é (o {@code CompanyProfile} é transiente e
 * nunca foi persistido) e {@link #PROFILE} e {@link #ASSURANCE} só existem no estado <b>de hoje</b>,
 * não no da época.
 *
 * <p>Sem esta declaração, reexecutar uma regra sobre um insumo ausente devolveria "não disparou" —
 * indistinguível de "rodou e o cliente estava limpo". É exatamente a ambiguidade que a V028 gastou
 * uma migration para eliminar na trilha, e reintroduzi-la no replay seria pior: ali ela apareceria
 * como <i>o motor de hoje decidiria diferente</i>, atribuindo a uma mudança de regra o que é falta
 * de dado.
 *
 * <p><b>Sem valor default no método.</b> Regra nova é obrigada pelo compilador a declarar o que
 * consome; esquecer não é possível. Declarar de menos ainda é possível — e é o que o
 * {@code RiskRuleContextDeclarationTest} confere, comparando a declaração com o que a regra de fato
 * chama.
 */
public enum ContextInput {

  /** {@code RiskContext.identity()} — reconstruível: {@code risk_scores.identity_check_id}. */
  IDENTITY,

  /** {@code RiskContext.screening()} — reconstruível: {@code risk_scores.screening_result_id}. */
  SCREENING,

  /** {@code RiskContext.company()} — <b>não</b> reconstruível: transiente, nunca persistido. */
  COMPANY,

  /** {@code RiskContext.profile()} — só o estado atual; {@code subject_profiles} não tem histórico. */
  PROFILE,

  /** {@code RiskContext.assurance()} — só o estado atual; {@code attempts} é contagem por janela. */
  ASSURANCE
}
