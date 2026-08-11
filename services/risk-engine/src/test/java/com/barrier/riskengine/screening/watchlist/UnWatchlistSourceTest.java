package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parsing da lista consolidada do CSNU. É o ponto que quebra quando a ONU muda o layout — e
 * quebrar em silêncio significaria decidir PLD-FT sem a lista de cumprimento mais direto que
 * existe (Lei 13.810/2019).
 */
class UnWatchlistSourceTest {

  private static final String XML =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <CONSOLIDATED_LIST dateGenerated="2026-08-01T00:00:00.000Z">
        <INDIVIDUALS>
          <INDIVIDUAL>
            <DATAID>6908051</DATAID>
            <FIRST_NAME>MOHAMMED</FIRST_NAME>
            <SECOND_NAME>OMAR</SECOND_NAME>
            <THIRD_NAME></THIRD_NAME>
            <FOURTH_NAME></FOURTH_NAME>
            <UN_LIST_TYPE>Taliban</UN_LIST_TYPE>
            <REFERENCE_NUMBER>TAi.004</REFERENCE_NUMBER>
            <INDIVIDUAL_ALIAS>
              <QUALITY>Good</QUALITY>
              <ALIAS_NAME>Mullah Omar</ALIAS_NAME>
            </INDIVIDUAL_ALIAS>
            <INDIVIDUAL_ALIAS>
              <QUALITY>Low</QUALITY>
              <ALIAS_NAME></ALIAS_NAME>
            </INDIVIDUAL_ALIAS>
          </INDIVIDUAL>
          <INDIVIDUAL>
            <FIRST_NAME>JOAO</FIRST_NAME>
            <SECOND_NAME>DA</SECOND_NAME>
            <THIRD_NAME>SILVA</THIRD_NAME>
            <FOURTH_NAME>SANTOS</FOURTH_NAME>
            <UN_LIST_TYPE>Al-Qaida</UN_LIST_TYPE>
            <REFERENCE_NUMBER>QDi.999</REFERENCE_NUMBER>
          </INDIVIDUAL>
        </INDIVIDUALS>
        <ENTITIES>
          <ENTITY>
            <FIRST_NAME>EMPRESA FANTASMA LTDA</FIRST_NAME>
            <UN_LIST_TYPE>Al-Qaida</UN_LIST_TYPE>
            <REFERENCE_NUMBER>QDe.123</REFERENCE_NUMBER>
            <ENTITY_ALIAS>
              <ALIAS_NAME>Fantasma Trading</ALIAS_NAME>
            </ENTITY_ALIAS>
          </ENTITY>
        </ENTITIES>
      </CONSOLIDATED_LIST>
      """;

  private static List<WatchlistRecord> parse() {
    return UnWatchlistSource.parse(XML.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void remontaONomeQuebradoEmVariosCampos() {
    assertThat(parse())
        .extracting(WatchlistRecord::name)
        .contains("MOHAMMED OMAR", "JOAO DA SILVA SANTOS", "EMPRESA FANTASMA LTDA");
  }

  /** Grafia alternativa é entrada própria: transliteração varia e o nome principal não cobre. */
  @Test
  void cadaApelidoViraUmaEntrada() {
    assertThat(parse())
        .extracting(WatchlistRecord::name)
        .contains("Mullah Omar", "Fantasma Trading");
  }

  @Test
  void apelidoVazioNaoViraEntrada() {
    assertThat(parse()).extracting(WatchlistRecord::name).doesNotContain("");
    assertThat(parse()).hasSize(5); // 3 nomes + 2 apelidos
  }

  /** O CSNU não publica CPF/CNPJ: o match é sempre por nome, ou seja, sempre indício. */
  @Test
  void entradasNaoTemDocumento() {
    assertThat(parse()).allSatisfy(r -> assertThat(r.document()).isNull());
  }

  @Test
  void classificaComoSancaoFinanceira() {
    assertThat(parse())
        .allSatisfy(
            r -> {
              assertThat(r.type()).isEqualTo(MatchType.SANCTION);
              assertThat(r.source()).isEqualTo("CSNU");
            });
  }

  /** A referência da ONU é o que o analista usa para achar a entrada na fonte. */
  @Test
  void detalheTrazReferenciaERegime() {
    assertThat(parse())
        .filteredOn(r -> r.name().equals("MOHAMMED OMAR"))
        .singleElement()
        .satisfies(r -> assertThat(r.detail()).contains("TAi.004", "Taliban"));
  }

  /**
   * XML de terceiro é entrada não confiável: com DOCTYPE habilitado, um arquivo publicado (ou
   * interceptado) poderia ler arquivo local ou fazer o serviço bater em endereço interno.
   */
  @Test
  void recusaXmlComDoctype() {
    String comDoctype =
        "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<CONSOLIDATED_LIST><INDIVIDUALS><INDIVIDUAL><FIRST_NAME>&xxe;</FIRST_NAME>"
            + "</INDIVIDUAL></INDIVIDUALS></CONSOLIDATED_LIST>";

    assertThatThrownBy(() -> UnWatchlistSource.parse(comDoctype.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ilegível");
  }

  @Test
  void xmlIlegivelFalhaComMensagemClara() {
    assertThatThrownBy(() -> UnWatchlistSource.parse("isto não é xml".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalStateException.class);
  }
}
