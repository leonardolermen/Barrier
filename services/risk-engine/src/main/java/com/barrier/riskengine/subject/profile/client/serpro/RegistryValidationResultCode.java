package com.barrier.riskengine.subject.profile.client.serpro;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Classifica o {@code code} do corpo de erro de {@code POST pessoa-fisica/validacao}.
 *
 * <p><b>Não é {@code SerproResultCode}</b> (o classificador de {@code assurance.client.serpro}
 * para {@code pessoa-fisica/app/resultado}, da etapa de biometria) — não podia ser: esta classe
 * mora em {@code subject.profile}, que não pode depender de {@code assurance} (regra de módulo,
 * ver CLAUDE.md), e os dois endpoints têm famílias de código quase disjuntas (biometria tem
 * {@code DV170–DV173} de PIN; esta tem {@code DV010–DV018}/{@code DV002} de dado inválido e
 * {@code DV200–DV213} de configuração de template, que a biometria não usa). A taxonomia de
 * transporte (5xx, 429, {@code DV150–DV152}/{@code DV300}) é a mesma família documentada e
 * reaproveitada aqui em espírito, não em código — não verificada ao vivo nesta etapa (ver
 * relatório), apoiada na sondagem já feita para a biometria.
 */
public final class RegistryValidationResultCode {

  private static final Pattern CODE_PATTERN = Pattern.compile("DV\\d{3}");

  /** CPF/nacionalidade/documento/sexo inválidos — requisição não tem como prosseguir. */
  private static final Set<String> DEFINITIVE_DATA = Set.of("DV010", "DV011", "DV012", "DV013",
      "DV014", "DV015", "DV016", "DV017", "DV018", "DV002");

  /** Menor de idade — desfecho definitivo e <b>cobrado</b>, não erro de transporte. */
  private static final String MINOR = "DV001";

  /** Integração Serpro/Senatran fora do ar — alimenta o disjuntor. */
  private static final Set<String> TRANSIENT = Set.of("DV150", "DV151", "DV152", "DV300");

  private RegistryValidationResultCode() {}

  public enum Classification {
    /** CPF/documento/sexo/nacionalidade inválidos no que foi enviado. */
    DEFINITIVE_INVALID_DATA,
    /** DV001: titular menor de idade — desfecho, cobrado, não erro. */
    MINOR_SUBJECT,
    /**
     * DV200–DV213: template RFB mal configurado (consentimento/base legal/metadados) —
     * responsabilidade nossa/do contrato, nunca do CPF do cliente. Tratar com mensagem que aponte
     * para configuração é o que evita alguém depurar o CPF errado por horas.
     */
    CONFIGURATION,
    /** Indisponibilidade do provedor — alimenta o disjuntor. */
    TRANSIENT_PROVIDER,
    /** 429 é cota nossa — backoff, não alimenta o disjuntor. */
    TRANSIENT_QUOTA,
    /** 4xx que não é do domínio do provedor (requisição nossa malformada). */
    NOT_PROVIDER,
    /** Código desconhecido ou corpo ilegível — mesma cautela de TRANSIENT_PROVIDER. */
    UNKNOWN
  }

  public static Classification classify(int httpStatus, String body, ObjectMapper objectMapper) {
    if (httpStatus == 429) {
      return Classification.TRANSIENT_QUOTA;
    }
    if (httpStatus == 500 || httpStatus == 502 || httpStatus == 503 || httpStatus == 504) {
      return Classification.TRANSIENT_PROVIDER;
    }
    String code = extractCode(body, objectMapper);
    if (code == null) {
      if (httpStatus == 400 || httpStatus == 401 || httpStatus == 403 || httpStatus == 404
          || httpStatus == 413) {
        return Classification.NOT_PROVIDER;
      }
      return Classification.UNKNOWN;
    }
    if (MINOR.equals(code)) {
      return Classification.MINOR_SUBJECT;
    }
    if (DEFINITIVE_DATA.contains(code)) {
      return Classification.DEFINITIVE_INVALID_DATA;
    }
    if (TRANSIENT.contains(code)) {
      return Classification.TRANSIENT_PROVIDER;
    }
    if (isConfiguration(code)) {
      return Classification.CONFIGURATION;
    }
    return Classification.UNKNOWN;
  }

  private static boolean isConfiguration(String code) {
    try {
      int n = Integer.parseInt(code.substring(2));
      return n >= 200 && n <= 213;
    } catch (RuntimeException e) {
      return false;
    }
  }

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
      // corpo não é JSON — cai para o regex abaixo
    }
    Matcher matcher = CODE_PATTERN.matcher(body);
    return matcher.find() ? matcher.group() : null;
  }
}
