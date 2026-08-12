package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PepWatchlistSourceTest {

  private static final String HEADER =
      "\"CPF\";\"Nome_PEP\";\"Sigla_Funcao\";\"Descricao_Funcao\";\"Nome_Orgao\"\n";

  private byte[] zipWith(String csv) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(bos)) {
      zos.putNextEntry(new ZipEntry("20260101_PEP.csv"));
      zos.write(csv.getBytes(StandardCharsets.ISO_8859_1));
      zos.closeEntry();
    }
    return bos.toByteArray();
  }

  private WatchlistBatch fetch(String csv) throws Exception {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("/download-de-dados/pep/20260101"))
        .andRespond(
            withSuccess(new ByteArrayResource(zipWith(csv)), MediaType.APPLICATION_OCTET_STREAM));
    return new PepWatchlistSource(builder.build(), "20260101").fetch();
  }

  @Test
  void classificaEntradasComoPepEnaoComoSancao() throws Exception {
    WatchlistBatch batch =
        fetch(HEADER + "\"***.982.247-**\";\"FULANO DE TAL\";\"CC\";\"Diretor\";\"Ministerio X\"\n");

    assertThat(batch.records()).hasSize(1);
    assertThat(batch.records().get(0).type()).isEqualTo(MatchType.PEP);
    assertThat(batch.records().get(0).source()).isEqualTo("PEP");
  }

  /**
   * O CPF mascarado não pode ir para {@code document}: o LocalWatchlistProvider casa por igualdade
   * exata e passaria a apontar PEP para o CPF errado.
   */
  @Test
  void cpfMascaradoViraDiscriminadorParcialENaoDocumentoDeMatchExato() throws Exception {
    WatchlistBatch batch =
        fetch(HEADER + "\"***.982.247-**\";\"FULANO DE TAL\";\"CC\";\"Diretor\";\"Ministerio X\"\n");

    WatchlistRecord record = batch.records().get(0);
    assertThat(record.document()).isNull();
    assertThat(record.documentPartial()).isEqualTo("982247");
  }

  /** Se a CGU passar a publicar o CPF completo, ele vale como match exato. */
  @Test
  void cpfCompletoViraDocumentoDeMatchExato() throws Exception {
    WatchlistBatch batch =
        fetch(HEADER + "\"529.982.247-25\";\"FULANO DE TAL\";\"CC\";\"Diretor\";\"Ministerio X\"\n");

    WatchlistRecord record = batch.records().get(0);
    assertThat(record.document()).isEqualTo("52998224725");
    assertThat(record.documentPartial()).isNull();
  }

  /** O cargo é o que o analista usa para julgar a exposição na revisão. */
  @Test
  void guardaAFuncaoComoDetalheParaOAnalista() throws Exception {
    WatchlistBatch batch =
        fetch(
            HEADER
                + "\"***.982.247-**\";\"FULANO DE TAL\";\"CC\";\"Diretor de Departamento\";\"Ministerio X\"\n");

    assertThat(batch.records().get(0).detail()).isEqualTo("Diretor de Departamento");
  }

  /** O dataset já trocou de nomenclatura entre publicações; não pode quebrar em silêncio. */
  @Test
  void toleraVariacaoDeRotuloNoCabecalho() throws Exception {
    WatchlistBatch batch =
        fetch(
            "\"CPF_PEP\";\"Nome do PEP\";\"Funcao\"\n"
                + "\"***.982.247-**\";\"FULANO DE TAL\";\"Diretor\"\n");

    assertThat(batch.records()).hasSize(1);
    assertThat(batch.records().get(0).name()).isEqualTo("FULANO DE TAL");
    assertThat(batch.records().get(0).documentPartial()).isEqualTo("982247");
  }

  @Test
  void declaraQueForneceCoberturaDePep() {
    assertThat(new PepWatchlistSource(RestClient.builder().build(), "20260101").provides())
        .containsExactly(MatchType.PEP);
  }
}
