package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfacWatchlistSourceTest {

  @Test
  void extraiCnpjBrasileiroDoRemarks() {
    // linha real da SDN (Victory Trading) — CNPJ no campo remarks (col 11)
    String csv =
        "56751,\"VICTORY TRADING INTERMEDIACAO DE NEGOCIOS COBRANCAS E TECNOLOGIA LTDA\","
            + "-0- ,\"ILLICIT-DRUGS-EO14059\",-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,"
            + "\"Organization Established Date 04 Aug 2021; Tax ID No. 42987643000110 (Brazil); "
            + "Linked To: DE OLIVEIRA SHIMADA, Victor Henrique.\"\n";

    List<WatchlistRecord> records = OfacWatchlistSource.parseSdn(csv);

    assertThat(records).hasSize(1);
    WatchlistRecord r = records.get(0);
    assertThat(r.source()).isEqualTo("OFAC");
    assertThat(r.type()).isEqualTo(MatchType.SANCTION);
    assertThat(r.document()).isEqualTo("42987643000110"); // agora indexado -> match exato
    assertThat(r.name()).startsWith("VICTORY TRADING");
  }

  @Test
  void extraiCpfBrasileiroFormatadoComPontuacaoDoRemarks() {
    // linha real da SDN (2026-07-14) — a maioria dos Tax ID BR vem formatada (com pontos/traço),
    // não em dígitos crus; a regex/normalização precisa cobrir os dois formatos.
    String csv =
        "34276,\"AL-MAGHRABI, Haytham Ahmad Shukri Ahmad\",\"individual\",\"SDGT\",-0- ,-0- ,-0- ,"
            + "-0- ,-0- ,-0- ,-0- ,\"DOB 07 Sep 1986; POB Egypt; nationality Egypt; Gender Male; "
            + "Secondary sanctions risk: section 1(b) of Executive Order 13224, as amended by "
            + "Executive Order 13886; Passport A09538178 (Egypt); Tax ID No. 238.624.338-97 "
            + "(Brazil).\"\n";

    List<WatchlistRecord> records = OfacWatchlistSource.parseSdn(csv);

    assertThat(records).hasSize(1);
    assertThat(records.get(0).document()).isEqualTo("23862433897");
  }

  @Test
  void extraiCnpjBrasileiroFormatadoComPontuacaoDoRemarks() {
    // CNPJ formatado (com pontos/barra/traço) — mesmo caso, tamanho 14.
    String csv =
        "11111,\"EMPRESA FORMATADA LTDA\",-0- ,\"SDNTK\",-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,"
            + "\"Tax ID No. 11.791.301/0001-05 (Brazil).\"\n";

    List<WatchlistRecord> records = OfacWatchlistSource.parseSdn(csv);

    assertThat(records).hasSize(1);
    assertThat(records.get(0).document()).isEqualTo("11791301000105");
  }

  @Test
  void semTaxIdBrasileiroFicaSemDocumento() {
    String csv = "306,\"BANCO NACIONAL DE CUBA\",-0- ,\"CUBA\",-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,-0- \n";

    List<WatchlistRecord> records = OfacWatchlistSource.parseSdn(csv);

    assertThat(records).hasSize(1);
    assertThat(records.get(0).document()).isNull();
    assertThat(records.get(0).name()).isEqualTo("BANCO NACIONAL DE CUBA");
  }

  @Test
  void naoConfundeTaxIdDeOutroPais() {
    String csv =
        "999,\"EMPRESA X\",-0- ,\"RUSSIA-EO14024\",-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,"
            + "\"Tax ID No. 5259113339 (Russia).\"\n";

    assertThat(OfacWatchlistSource.parseSdn(csv).get(0).document()).isNull();
  }

  @Test
  void apelidosSaemSemDocumento() {
    String csv = "306,220,\"aka\",\"NATIONAL BANK OF CUBA\",-0- \n";

    List<WatchlistRecord> records = OfacWatchlistSource.parseAlt(csv);

    assertThat(records).hasSize(1);
    assertThat(records.get(0).name()).isEqualTo("NATIONAL BANK OF CUBA");
    assertThat(records.get(0).document()).isNull();
  }
}
