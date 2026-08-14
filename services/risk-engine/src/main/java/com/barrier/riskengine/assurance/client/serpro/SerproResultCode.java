package com.barrier.riskengine.assurance.client.serpro;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Classifica o {@code code} do corpo de erro de {@code POST pessoa-fisica/app/resultado} —
 * sondado ao vivo contra o ambiente de demonstração (ver relatório).
 *
 * <p><b>Os dois documentos oficiais do Serpro se contradizem sobre DV171.</b> A tabela de
 * códigos de retorno agrupa {@code DV170–DV173} em bloco como "Permanent Failures
 * (Non-Retryable)". O quick start diz, textualmente, que em {@code 422/DV171} o cliente deve
 * "repetir a chamada para obter o resultado em breve, pois indica que a prova de vida ainda não
 * foi realizada pelo usuário final". A leitura correta é a do quick start — a tabela agrupou mal:
 * {@code DV171} é <b>pendente</b>, repolável; {@code DV170} (PIN não encontrado), {@code DV172}
 * (expirado) e {@code DV173} (tentativas esgotadas) são, esses sim, definitivos. Tratar DV171
 * como falha permanente (a leitura ingênua da tabela) faz o poller nunca buscar resultado
 * nenhum — nenhum PIN jamais teria tempo de o cidadão completar a captura antes do encerramento.
 *
 * <p><b>Corpo do 422 é JSON com forma conhecida</b> (capturado ao vivo): {@code
 * {"code":"DV170","args":["APP_PIN_NOT_FOUND"],"link":"..."}}. Mas <b>nem todo corpo de erro é
 * JSON</b> — o 400 de validação de tamanho do PIN observado ao vivo veio em texto puro
 * ({@code "pin : valor deve possuir exatamente 9 caracteres"}), então o parsing cai para um
 * regex {@code DV\d{3}} sobre o corpo cru quando não há JSON válido.
 */
final class SerproResultCode {

  private static final Pattern CODE_PATTERN = Pattern.compile("DV\\d{3}");

  /** Único código pendente/repolável. */
  private static final String PENDING = "DV171";

  /** PIN não encontrado, expirado, tentativas esgotadas — e prova de vida explicitamente reprovada. */
  private static final java.util.Set<String> DEFINITIVE_FAIL =
      java.util.Set.of("DV170", "DV172", "DV173", "DV061", "DV062");

  /**
   * Qualidade/formato do artefato apresentado (imagem facial, CNH, QR Code) — repetir a
   * <b>tentativa</b> poderia corrigir, mas esta consulta específica não tem mais o que esperar.
   */
  private static final java.util.List<int[]> DEFINITIVE_INCONCLUSIVE_RANGES =
      java.util.List.of(new int[] {40, 53}, new int[] {79, 89}, new int[] {100, 112});

  /** Integração do próprio Serpro fora do ar (não é sobre o cidadão) — backoff, alimenta o disjuntor. */
  private static final java.util.Set<String> TRANSIENT = java.util.Set.of("DV150", "DV151", "DV152", "DV300");

  private SerproResultCode() {}

  enum Classification {
    /** DV171: ainda não há desfecho. Não cobrado (ver Javadoc de {@code AssuranceResultPoller}). */
    PENDING_RETRY,
    /** Encerra o check como FAIL — prova de vida/PIN definitivamente reprovados. */
    DEFINITIVE_FAIL,
    /** Encerra o check como INCONCLUSIVE — qualidade/formato do artefato apresentado. */
    DEFINITIVE_INCONCLUSIVE,
    /** Indisponibilidade do lado do provedor (integração, Senatran) ou 5xx — alimenta o disjuntor. */
    TRANSIENT_PROVIDER,
    /** 429 de cota — transitório, mas a cota é nossa: não alimenta o disjuntor (ver Javadoc). */
    TRANSIENT_QUOTA,
    /** 400/401/403/404/413: requisição nossa malformada, não indisponibilidade do provedor. */
    NOT_PROVIDER,
    /** Código desconhecido ou corpo ilegível — tratado com a mesma cautela de TRANSIENT_PROVIDER. */
    UNKNOWN
  }

  static Classification classify(int httpStatus, String body, ObjectMapper objectMapper) {
    if (httpStatus == 429) {
      return Classification.TRANSIENT_QUOTA;
    }
    if (httpStatus == 500 || httpStatus == 502 || httpStatus == 503 || httpStatus == 504) {
      return Classification.TRANSIENT_PROVIDER;
    }
    if (httpStatus == 400 || httpStatus == 401 || httpStatus == 403 || httpStatus == 404 || httpStatus == 413) {
      return Classification.NOT_PROVIDER;
    }
    String code = extractCode(body, objectMapper);
    if (code == null) {
      return Classification.UNKNOWN;
    }
    if (PENDING.equals(code)) {
      return Classification.PENDING_RETRY;
    }
    if (DEFINITIVE_FAIL.contains(code)) {
      return Classification.DEFINITIVE_FAIL;
    }
    if (TRANSIENT.contains(code)) {
      return Classification.TRANSIENT_PROVIDER;
    }
    if (inAnyRange(code)) {
      return Classification.DEFINITIVE_INCONCLUSIVE;
    }
    return Classification.UNKNOWN;
  }

  private static boolean inAnyRange(String code) {
    int n;
    try {
      n = Integer.parseInt(code.substring(2));
    } catch (RuntimeException e) {
      return false;
    }
    for (int[] range : DEFINITIVE_INCONCLUSIVE_RANGES) {
      if (n >= range[0] && n <= range[1]) {
        return true;
      }
    }
    return false;
  }

  /** JSON {@code {"code":"DVxxx",...}} primeiro; corpo em texto puro cai para o regex {@code DV\d{3}}. */
  private static String extractCode(String body, ObjectMapper objectMapper) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(body);
      JsonNode code = node.get("code");
      if (code != null && code.isString()) {
        return code.asString();
      }
    } catch (RuntimeException e) {
      // corpo não é JSON — cai para o regex abaixo (ex.: erro de validação em texto puro)
    }
    Matcher matcher = CODE_PATTERN.matcher(body);
    return matcher.find() ? matcher.group() : null;
  }
}
