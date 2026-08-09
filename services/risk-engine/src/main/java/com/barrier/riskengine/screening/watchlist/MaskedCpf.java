package com.barrier.riskengine.screening.watchlist;

/**
 * Trata CPF publicado de forma parcial. O padrão da administração pública brasileira é divulgar
 * {@code ***.123.456-**}: escondem-se os 3 primeiros dígitos e os 2 verificadores, restando os 6
 * centrais — posições 4 a 9 do CPF.
 *
 * <p>Seis dígitos não identificam ninguém sozinhos (há ~100 mil CPFs para cada combinação), mas
 * combinados ao nome reduzem drasticamente a colisão: é a diferença entre uma lista de PEP
 * utilizável e uma que manda todo homônimo para a fila de análise.
 */
final class MaskedCpf {

  private static final int VISIBLE_START = 3;
  private static final int VISIBLE_END = 9;
  private static final int VISIBLE_LENGTH = VISIBLE_END - VISIBLE_START;
  private static final int FULL_LENGTH = 11;

  private MaskedCpf() {}

  /**
   * Extrai os 6 dígitos centrais de um CPF completo, para comparar com o que a lista publicou.
   *
   * @return {@code null} se não for um CPF de 11 dígitos (CNPJ, por exemplo)
   */
  static String centralDigitsOf(String cpfDigits) {
    if (cpfDigits == null || cpfDigits.length() != FULL_LENGTH) {
      return null;
    }
    return cpfDigits.substring(VISIBLE_START, VISIBLE_END);
  }

  /**
   * Interpreta o valor publicado pela lista.
   *
   * @return os 6 dígitos centrais quando o valor é um CPF mascarado; {@code null} quando não é
   *     reconhecível como tal (documento completo, campo vazio, ou formato inesperado — casos que
   *     o chamador trata como "sem discriminador", nunca como "não casa")
   */
  static String parsePublished(String published) {
    String digits = CsvSupport.digitsOnly(published);
    if (digits == null) {
      return null;
    }
    if (digits.length() == FULL_LENGTH) {
      return centralDigitsOf(digits);
    }
    return digits.length() == VISIBLE_LENGTH ? digits : null;
  }

  /** Indica se o valor publicado é um documento completo (e não uma máscara). */
  static boolean isComplete(String published) {
    String digits = CsvSupport.digitsOnly(published);
    return digits != null && (digits.length() == FULL_LENGTH || digits.length() == 14);
  }
}
