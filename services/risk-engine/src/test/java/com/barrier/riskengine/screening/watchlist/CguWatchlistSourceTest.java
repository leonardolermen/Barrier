package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CguWatchlistSourceTest {

  /** Instância concreta de teste com data fixa. */
  private static class TestCguSource extends CguWatchlistSource {
    TestCguSource(RestClient client) {
      super(client);
    }

    @Override
    public String source() {
      return "CEIS";
    }

    @Override
    protected String pathSegment() {
      return "ceis";
    }

    @Override
    protected String referenceDate() {
      return "20260101";
    }
  }

  private byte[] zipWith(String csv) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(bos)) {
      zos.putNextEntry(new ZipEntry("20260101_CEIS.csv"));
      zos.write(csv.getBytes(StandardCharsets.ISO_8859_1));
      zos.closeEntry();
    }
    return bos.toByteArray();
  }

  @Test
  void baixaZipEParseiaLinhasPorDocumento() throws Exception {
    String csv =
        "\"CPF OU CNPJ DO SANCIONADO\";\"NOME INFORMADO PELO ÓRGÃO SANCIONADOR\";\"TIPO DA SANÇÃO\"\n"
            + "\"11.444.777/0001-61\";\"EMPRESA INIDÔNEA LTDA\";\"Inidônea\"\n"
            + "\"529.982.247-25\";\"FULANO SANCIONADO\";\"Suspensa\"\n";

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("/download-de-dados/ceis/20260101"))
        .andRespond(
            withSuccess(new ByteArrayResource(zipWith(csv)), MediaType.APPLICATION_OCTET_STREAM));

    WatchlistBatch batch = new TestCguSource(builder.build()).fetch();

    assertThat(batch.records()).hasSize(2);
    WatchlistRecord first = batch.records().get(0);
    assertThat(first.source()).isEqualTo("CEIS");
    assertThat(first.type()).isEqualTo(MatchType.SANCTION);
    assertThat(first.document()).isEqualTo("11444777000161");
    assertThat(first.name()).isEqualTo("EMPRESA INIDÔNEA LTDA");
    assertThat(batch.records().get(1).document()).isEqualTo("52998224725");
  }

  @Test
  void recuaUmDiaQuandoPacoteDoDiaAindaNaoFoiPublicado() throws Exception {
    // Comportamento real observado: o pacote do dia de referência costuma dar 403 (ainda não
    // publicado, ou o bucket já expurgou); a fonte precisa recuar um dia e tentar de novo.
    String csv =
        "\"CPF OU CNPJ DO SANCIONADO\";\"NOME INFORMADO PELO ÓRGÃO SANCIONADOR\";\"TIPO DA SANÇÃO\"\n"
            + "\"11.444.777/0001-61\";\"EMPRESA INIDÔNEA LTDA\";\"Inidônea\"\n";

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("/download-de-dados/ceis/20260101")).andRespond(withStatus(HttpStatus.FORBIDDEN));
    server
        .expect(requestTo("/download-de-dados/ceis/20251231"))
        .andRespond(
            withSuccess(new ByteArrayResource(zipWith(csv)), MediaType.APPLICATION_OCTET_STREAM));

    WatchlistBatch batch = new TestCguSource(builder.build()).fetch();

    assertThat(batch.records()).hasSize(1);
    assertThat(batch.version()).isEqualTo("ceis-20251231");
    server.verify();
  }

  @Test
  void esgotaTentativasELancaExcecao() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("/download-de-dados/ceis/20260101")).andRespond(withStatus(HttpStatus.FORBIDDEN));
    server.expect(requestTo("/download-de-dados/ceis/20251231")).andRespond(withStatus(HttpStatus.FORBIDDEN));
    server.expect(requestTo("/download-de-dados/ceis/20251230")).andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> new TestCguSource(builder.build()).fetch())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Nenhum pacote");
    server.verify();
  }
}
