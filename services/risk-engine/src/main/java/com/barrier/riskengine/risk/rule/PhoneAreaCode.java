package com.barrier.riskengine.risk.rule;

import java.util.Map;

/** Tabela DDD → UF (plano de numeração ANATEL), usada por {@link ConsistencyRiskRule}. */
final class PhoneAreaCode {

  private static final Map<String, String> DDD_TO_UF =
          Map.<String, String>ofEntries(
              Map.entry("11", "SP"), Map.entry("12", "SP"), Map.entry("13", "SP"),
              Map.entry("14", "SP"), Map.entry("15", "SP"), Map.entry("16", "SP"),
              Map.entry("17", "SP"), Map.entry("18", "SP"), Map.entry("19", "SP"),
              Map.entry("21", "RJ"), Map.entry("22", "RJ"), Map.entry("24", "RJ"),
              Map.entry("27", "ES"), Map.entry("28", "ES"),
              Map.entry("31", "MG"), Map.entry("32", "MG"), Map.entry("33", "MG"),
              Map.entry("34", "MG"), Map.entry("35", "MG"), Map.entry("37", "MG"),
              Map.entry("38", "MG"),
              Map.entry("41", "PR"), Map.entry("42", "PR"), Map.entry("43", "PR"),
              Map.entry("44", "PR"), Map.entry("45", "PR"), Map.entry("46", "PR"),
              Map.entry("47", "SC"), Map.entry("48", "SC"), Map.entry("49", "SC"),
              Map.entry("51", "RS"), Map.entry("53", "RS"), Map.entry("54", "RS"),
              Map.entry("55", "RS"),
              Map.entry("61", "DF"),
              Map.entry("62", "GO"), Map.entry("64", "GO"),
              Map.entry("63", "TO"),
              Map.entry("65", "MT"), Map.entry("66", "MT"),
              Map.entry("67", "MS"),
              Map.entry("68", "AC"),
              Map.entry("69", "RO"),
              Map.entry("71", "BA"), Map.entry("73", "BA"), Map.entry("74", "BA"),
              Map.entry("75", "BA"), Map.entry("77", "BA"),
              Map.entry("79", "SE"),
              Map.entry("81", "PE"), Map.entry("87", "PE"),
              Map.entry("82", "AL"),
              Map.entry("83", "PB"),
              Map.entry("84", "RN"),
              Map.entry("85", "CE"), Map.entry("88", "CE"),
              Map.entry("86", "PI"), Map.entry("89", "PI"),
              Map.entry("91", "PA"), Map.entry("93", "PA"), Map.entry("94", "PA"),
              Map.entry("92", "AM"), Map.entry("97", "AM"),
              Map.entry("95", "RR"),
              Map.entry("96", "AP"),
              Map.entry("98", "MA"), Map.entry("99", "MA"));

  private PhoneAreaCode() {}

  /** UF esperada para o DDD extraído do telefone; {@code null} se não conseguir extrair/DDD desconhecido. */
  static String ufOf(String phone) {
    if (phone == null) {
      return null;
    }
    String digits = phone.replaceAll("\\D", "");
    if (digits.startsWith("55") && digits.length() > 10) {
      digits = digits.substring(2);
    }
    if (digits.length() < 10) {
      return null;
    }
    return DDD_TO_UF.get(digits.substring(0, 2));
  }
}
