package com.barrier.riskengine.monitoring.domain;

/**
 * Um alerta disparado, com o número que o disparou.
 *
 * <p>A evidência é obrigatória e não é enfeite: alerta que diz "backlog alto" sem dizer <i>quanto</i>
 * e <i>contra o quê</i> obriga quem está de plantão a ir ao banco antes de decidir se acorda alguém.
 * Mesma exigência que o motor de risco faz das suas regras — fator explicável, nunca só o veredito.
 *
 * @param code código estável do alerta (vocabulário do ecossistema Origem: {@code backlog_analise},
 *     {@code vol_hora_baixo}, {@code aprov_auto_alto}...)
 * @param evidence o observado, o esperado e a margem — em texto pronto para o canal
 */
public record Alert(String code, Severity severity, String message, String evidence) {

  /** Quão rápido alguém precisa olhar. Não é o nível de risco do cliente. */
  public enum Severity {
    /** Anomalia que merece investigação no horário comercial. */
    WARNING,
    /** Pipeline degradado ou controle possivelmente cego — olhar agora. */
    CRITICAL
  }

  public static Alert warning(String code, String message, String evidence) {
    return new Alert(code, Severity.WARNING, message, evidence);
  }

  public static Alert critical(String code, String message, String evidence) {
    return new Alert(code, Severity.CRITICAL, message, evidence);
  }
}
