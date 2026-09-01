package com.barrier.riskengine.policy;

import java.time.Instant;

/**
 * Quem definiu o valor de um parâmetro de regra, e quando.
 *
 * <p>Deliberadamente <b>não</b> reconstrói o valor efetivo: esse já está gravado junto da decisão,
 * em {@code evaluated_json}, para toda regra que rodou — inclusive as que passaram. Reconstruí-lo
 * daqui criaria uma segunda fonte para o mesmo fato, e duas fontes divergem. O que falta, e é o que
 * este tipo carrega, é a autoria.
 *
 * @param source de onde o valor veio no instante consultado
 * @param changedBy autor do override; {@code null} quando o valor veio do default global (é código,
 *     e a autoria dele é o {@code ENGINE_VERSION}) ou quando a origem é desconhecida
 */
public record ParamAuthorship(
    String paramKey,
    ParamSource source,
    String value,
    String changedBy,
    Instant changedAt,
    PolicyProvenance provenance) {

  /** De onde saiu o parâmetro que a regra usou. */
  public enum ParamSource {
    /** Override do tenant vigente no instante da decisão. */
    TENANT_OVERRIDE,
    /** Nenhum override vigia: o valor veio do default global, que é código. */
    GLOBAL_DEFAULT,
    /** Há histórico de override, mas todo posterior à decisão — a origem de então não é apurável. */
    UNKNOWN
  }
}
