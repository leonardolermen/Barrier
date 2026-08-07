package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.repository.WatchlistEntryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FuzzyNameWatchlistProviderTest {

  @Mock WatchlistEntryRepository repository;

  private FuzzyNameWatchlistProvider provider() {
    return new FuzzyNameWatchlistProvider(repository, 0.90, 6);
  }

  private WatchlistQuery query(String name) {
    return new WatchlistQuery("CPF", "00000000000", name);
  }

  private WatchlistRecord ofac(String name) {
    return new WatchlistRecord("OFAC", MatchType.SANCTION, null, name, "SDN");
  }

  @Test
  void nomeQuaseIgualComAcentoEViraHit() {
    when(repository.findNameEntries()).thenReturn(List.of(ofac("OSAMA BIN LADEN")));

    List<WatchlistEntry> hits = provider().search(query("Osama Bin Láden"));

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).source()).isEqualTo("OFAC");
    assertThat(hits.get(0).detail()).contains("match por nome");
  }

  @Test
  void nomeDiferenteNaoCasa() {
    when(repository.findNameEntries()).thenReturn(List.of(ofac("OSAMA BIN LADEN")));

    assertThat(provider().search(query("Maria da Silva"))).isEmpty();
  }

  @Test
  void apelidoIngeridoComoEntradaTambemCasa() {
    when(repository.findNameEntries())
        .thenReturn(List.of(ofac("USAMA BIN LADIN"))); // aka grafado diferente

    assertThat(provider().search(query("Usama Bin Ladin"))).hasSize(1);
  }

  @Test
  void nomeCurtoNaoAcionaMatch() {
    // abaixo do min-name-length: evita falso positivo, nem consulta o repositório
    assertThat(provider().search(query("Ana"))).isEmpty();
  }

  private WatchlistRecord pep(String name, String documentPartial) {
    return new WatchlistRecord("PEP", MatchType.PEP, null, documentPartial, name, "Diretor");
  }

  /**
   * Sem o discriminador, a lista de PEP da CGU (centenas de milhares de servidores) mandaria todo
   * homônimo para a fila de revisão. Os 6 dígitos centrais publicados descartam o candidato.
   */
  @Test
  void cpfParcialDivergenteDescartaOCandidatoMesmoComNomeIdentico() {
    when(repository.findNameEntries()).thenReturn(List.of(pep("JOAO DA SILVA", "111111")));

    // CPF consultado 529.982.247-25 -> centrais "982247", que não batem com "111111"
    List<WatchlistEntry> hits =
        provider().search(new WatchlistQuery("CPF", "52998224725", "Joao da Silva"));

    assertThat(hits).isEmpty();
  }

  @Test
  void cpfParcialCoincidenteConfirmaOMatchERegistraNaEvidencia() {
    when(repository.findNameEntries()).thenReturn(List.of(pep("JOAO DA SILVA", "982247")));

    List<WatchlistEntry> hits =
        provider().search(new WatchlistQuery("CPF", "52998224725", "Joao da Silva"));

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).type()).isEqualTo(MatchType.PEP);
    assertThat(hits.get(0).detail()).contains("CPF parcial confirmado");
  }

  /** Entrada com CPF parcial nunca é a mesma entidade que um CNPJ consultado. */
  @Test
  void consultaDeCnpjNaoCasaComEntradaDeCpfParcial() {
    when(repository.findNameEntries()).thenReturn(List.of(pep("ACME COMERCIO", "982247")));

    assertThat(provider().search(new WatchlistQuery("CNPJ", "11444777000161", "Acme Comercio")))
        .isEmpty();
  }

  /** Entradas sem discriminador (OFAC) seguem decidindo só pelo nome, como antes. */
  @Test
  void entradaSemCpfParcialSegueDecidindoApenasPeloNome() {
    when(repository.findNameEntries()).thenReturn(List.of(ofac("OSAMA BIN LADEN")));

    assertThat(provider().search(new WatchlistQuery("CPF", "52998224725", "Osama Bin Laden")))
        .hasSize(1);
  }
}
