package com.barrier.riskengine.screening.domain;

/**
 * Por qual atributo o apontamento casou com a lista restritiva. Determina o <b>grau de confiança
 * no match</b>, que é diferente da gravidade da lista.
 *
 * <p>Um CPF/CNPJ é identificador único: casou, é a mesma entidade. Um nome não é — "JOSE SILVA"
 * casa com milhares de pessoas, e a comparação é fuzzy (Jaro-Winkler), sem data de nascimento,
 * país ou qualquer outro qualificador que permita desempatar. Por isso match por nome é
 * <i>indício</i> e vai para revisão humana; match por documento é <i>evidência</i> e bloqueia.
 *
 * <p>Tratar os dois igual (o comportamento anterior) significava reprovar automaticamente um
 * homônimo de sancionado, sem revisão e sem recurso.
 */
public enum MatchBasis {
  /** Casou por CPF/CNPJ exato — identificação inequívoca. */
  DOCUMENT,
  /** Casou por similaridade de nome — precisa de julgamento humano. */
  NAME
}
