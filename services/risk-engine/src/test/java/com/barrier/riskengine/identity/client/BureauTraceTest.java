package com.barrier.riskengine.identity.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * O rastro é o que torna "consultamos o bureau" verificável contra o provedor — hoje é afirmação
 * nossa sobre nós mesmos, sem nada que a confira numa inspeção ou numa contestação.
 */
class BureauTraceTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private static final String RESPOSTA =
      """
      {"QueryId":"6634d1b1e4b0a1","QueryDate":"2026-08-11T10:00:00Z",
       "Result":[{"BasicData":{"TaxIdNumber":"11144477735","Name":"FULANO DE TAL",
       "MotherName":"MARIA DE TAL","TaxIdStatus":"REGULAR"}}]}
      """;

  @Test
  void extraiOIdentificadorDaConsulta() {
    BureauTrace trace = BureauTrace.from(mapper, RESPOSTA, "QueryId", true);

    assertThat(trace.providerReference()).isEqualTo("6634d1b1e4b0a1");
  }

  /**
   * Nome da mãe é fator de autenticação, e o projeto já decidiu guardar só o resultado da
   * comparação. Guardar o payload bruto sem redigir desfaria essa decisão em silêncio.
   */
  @Test
  void redigeOsCamposQueOProjetoDecidiuNaoGuardar() {
    BureauTrace trace = BureauTrace.from(mapper, RESPOSTA, "QueryId", true);

    assertThat(trace.rawResponse()).doesNotContain("MARIA DE TAL").contains("[redigido]");
    // o que sustentou a decisão continua lá, senão o rastro não serve de evidência
    assertThat(trace.rawResponse()).contains("REGULAR", "FULANO DE TAL");
  }

  /** Payload é dado pessoal: dá para guardar só o ponteiro enquanto a Fase 6 não chega. */
  @Test
  void comPersistenciaDesligadaGuardaApenasOIdentificador() {
    BureauTrace trace = BureauTrace.from(mapper, RESPOSTA, "QueryId", false);

    assertThat(trace.providerReference()).isEqualTo("6634d1b1e4b0a1");
    assertThat(trace.rawResponse()).isNull();
  }

  /** Fonte sem identificador de consulta (BrasilAPI): ausência registrada, não id inventado. */
  @Test
  void respostaSemOCampoDeReferenciaFicaComIdNulo() {
    BureauTrace trace = BureauTrace.from(mapper, "{\"Result\":[]}", "QueryId", true);

    assertThat(trace.providerReference()).isNull();
    assertThat(trace.rawResponse()).contains("Result");
  }

  /** Rastro é evidência, não decisão: corpo ilegível não pode derrubar a verificação. */
  @Test
  void corpoIlegivelNaoQuebra() {
    BureauTrace trace = BureauTrace.from(mapper, "isto não é json", "QueryId", true);

    assertThat(trace.providerReference()).isNull();
    assertThat(trace.rawResponse()).isNull();
  }

  @Test
  void corpoVazioNaoQuebra() {
    assertThat(BureauTrace.from(mapper, null, "QueryId", true).providerReference()).isNull();
    assertThat(BureauTrace.from(mapper, "", "QueryId", true).rawResponse()).isNull();
  }
}
