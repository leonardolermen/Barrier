package com.barrier.riskengine.assurance.client.serpro;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Taxonomia de {@code SerproResultCode.classify} — a tabela oficial de códigos e o quick start do
 * Serpro se contradizem sobre DV171 (ver Javadoc da classe); estes testes fixam a leitura correta
 * (quick start: DV171 é pendente) e a taxonomia completa do restante do briefing.
 */
class SerproResultCodeTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private SerproResultCode.Classification classify(int status, String body) {
    return SerproResultCode.classify(status, body, objectMapper);
  }

  @Test
  void dv171EPendenteMesmoAgrupadaComoPermanenteNaTabelaOficial() {
    assertThat(classify(422, "{\"code\":\"DV171\",\"args\":[],\"link\":\"x\"}"))
        .isEqualTo(SerproResultCode.Classification.PENDING_RETRY);
  }

  @Test
  void dv170Dv172Dv173SaoDefinitivas() {
    assertThat(classify(422, "{\"code\":\"DV170\"}")).isEqualTo(SerproResultCode.Classification.DEFINITIVE_FAIL);
    assertThat(classify(422, "{\"code\":\"DV172\"}")).isEqualTo(SerproResultCode.Classification.DEFINITIVE_FAIL);
    assertThat(classify(422, "{\"code\":\"DV173\"}")).isEqualTo(SerproResultCode.Classification.DEFINITIVE_FAIL);
  }

  @Test
  void familiasDeQualidadeSaoInconclusivas() {
    assertThat(classify(422, "{\"code\":\"DV045\"}"))
        .isEqualTo(SerproResultCode.Classification.DEFINITIVE_INCONCLUSIVE);
    assertThat(classify(422, "{\"code\":\"DV085\"}"))
        .isEqualTo(SerproResultCode.Classification.DEFINITIVE_INCONCLUSIVE);
    assertThat(classify(422, "{\"code\":\"DV105\"}"))
        .isEqualTo(SerproResultCode.Classification.DEFINITIVE_INCONCLUSIVE);
  }

  @Test
  void provaDeVidaReprovadaEDefinitiva() {
    assertThat(classify(422, "{\"code\":\"DV061\"}")).isEqualTo(SerproResultCode.Classification.DEFINITIVE_FAIL);
    assertThat(classify(422, "{\"code\":\"DV062\"}")).isEqualTo(SerproResultCode.Classification.DEFINITIVE_FAIL);
  }

  @Test
  void integracaoESenatranIndisponiveisSaoTransitorias() {
    assertThat(classify(422, "{\"code\":\"DV150\"}")).isEqualTo(SerproResultCode.Classification.TRANSIENT_PROVIDER);
    assertThat(classify(422, "{\"code\":\"DV300\"}")).isEqualTo(SerproResultCode.Classification.TRANSIENT_PROVIDER);
  }

  @Test
  void http5xxSaoTransitorios() {
    assertThat(classify(500, null)).isEqualTo(SerproResultCode.Classification.TRANSIENT_PROVIDER);
    assertThat(classify(503, null)).isEqualTo(SerproResultCode.Classification.TRANSIENT_PROVIDER);
  }

  @Test
  void http429ENossaCotaNaoDoProvedor() {
    assertThat(classify(429, "{\"code\":\"900807\",\"message\":\"Message throttled out\"}"))
        .isEqualTo(SerproResultCode.Classification.TRANSIENT_QUOTA);
  }

  @Test
  void http4xxDeRequisicaoMalformadaNaoEDoProvedor() {
    assertThat(classify(400, "pin : valor deve possuir exatamente 9 caracteres"))
        .isEqualTo(SerproResultCode.Classification.NOT_PROVIDER);
    assertThat(classify(401, null)).isEqualTo(SerproResultCode.Classification.NOT_PROVIDER);
    assertThat(classify(404, null)).isEqualTo(SerproResultCode.Classification.NOT_PROVIDER);
  }

  @Test
  void codigoDesconhecidoEUnknown() {
    assertThat(classify(422, "{\"code\":\"DV999\"}")).isEqualTo(SerproResultCode.Classification.UNKNOWN);
  }

  /** Corpo em texto puro (não JSON) — observado ao vivo no erro de validação do tamanho do PIN. */
  @Test
  void corpoEmTextoPuroCaiParaORegexSemQuebrar() {
    assertThat(classify(422, "algo com DV171 no meio do texto"))
        .isEqualTo(SerproResultCode.Classification.PENDING_RETRY);
    assertThat(classify(422, "texto sem nenhum codigo")).isEqualTo(SerproResultCode.Classification.UNKNOWN);
  }
}
