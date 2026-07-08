package com.barrier.riskengine.screening.watchlist;

import java.util.ArrayList;
import java.util.List;

/** Utilidades de parsing de CSV com aspas (RFC-4180 simplificado), delimitador configurável. */
final class CsvSupport {

  private CsvSupport() {}

  /** Divide uma linha respeitando campos entre aspas duplas (com {@code ""} escapado). */
  static List<String> split(String line, char delimiter) {
    List<String> fields = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            current.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          current.append(c);
        }
      } else if (c == '"') {
        inQuotes = true;
      } else if (c == delimiter) {
        fields.add(current.toString().trim());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    fields.add(current.toString().trim());
    return fields;
  }

  /** Mantém apenas dígitos (para normalizar CPF/CNPJ vindos formatados). */
  static String digitsOnly(String value) {
    if (value == null) {
      return null;
    }
    String digits = value.replaceAll("\\D", "");
    return digits.isBlank() ? null : digits;
  }
}
