package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.ScreenedParty;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.repository.interfaces.WatchlistEntryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FuzzyNameWatchlistProviderTest {

  @Mock WatchlistEntryRepository repository;

  private FuzzyNameWatchlistProvider provider() {
    return new FuzzyNameWatchlistProvider(repository, 0.90, 6, 0.45);
  }

  private WatchlistQuery query(String name) {
    return new WatchlistQuery("CPF", "00000000000", name);
  }

  private WatchlistRecord ofac(String name) {
    return new WatchlistRecord("OFAC", MatchType.SANCTION, null, name, "SDN");
  }

  @Test
  void nomeQuaseIgualComAcentoEViraHit() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(ofac("OSAMA BIN LADEN")));

    List<WatchlistEntry> hits = provider().search(query("Osama Bin Láden"));

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).source()).isEqualTo("OFAC");
    assertThat(hits.get(0).detail()).contains("match por nome");
  }

  @Test
  void nomeDiferenteNaoCasa() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(ofac("OSAMA BIN LADEN")));

    assertThat(provider().search(query("Maria da Silva"))).isEmpty();
  }

  @Test
  void apelidoIngeridoComoEntradaTambemCasa() {
    when(repository.findNameCandidates(any(), anyDouble()))
        .thenReturn(List.of(ofac("USAMA BIN LADIN"))); // aka grafado diferente

    assertThat(provider().search(query("Usama Bin Ladin"))).hasSize(1);
  }

  /**
   * Regressão do falso negativo mais caro do screening: as listas de sanção publicam o nome como
   * {@code SOBRENOME, Nome}. Comparando as strings inteiras com Jaro-Winkler (que premia prefixo
   * igual), a mesma pessoa com a ordem invertida ficava perto de 0.5 e nunca casava — o controle
   * rodava, registrava que rodou, e não encontrava ninguém.
   */
  @Test
  void nomeNaOrdemInvertidaDaListaCasa() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(ofac("SILVA, JOSE ANTONIO")));

    assertThat(provider().search(query("Jose Antonio da Silva"))).hasSize(1);
  }

  /** O cadastro informa o nome completo; a lista publica só parte dele (ou o contrário). */
  @Test
  void casaEmQualquerDirecaoQuandoUmNomeTemMaisTokensQueOOutro() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(ofac("JOSE ANTONIO SILVA SANTOS")));

    assertThat(provider().search(query("Jose Antonio Silva"))).hasSize(1);
  }

  @Test
  void casaNomeDeListaMaisCurtoQueOCadastro() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(ofac("ANTONIO SANTOS")));

    assertThat(provider().search(query("Antonio Santos de Oliveira"))).hasSize(1);
  }

  /** Erro de digitação dentro de um token ainda casa; sobrenome diferente, não. */
  @Test
  void toleraErroDeDigitacaoMasSeparaSobrenomesDiferentes() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(ofac("ANTONIO CARLOS PEREIRA")));

    assertThat(provider().search(query("Antonio Carlos Pereria"))).hasSize(1);
    assertThat(provider().search(query("Antonio Carlos Machado"))).isEmpty();
  }

  /**
   * O primeiro nome igual não pode bastar — era o falso positivo simétrico do bônus de prefixo do
   * Jaro-Winkler sobre a string inteira.
   */
  @Test
  void primeiroNomeIgualNaoBastaParaCasar() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(ofac("CARLOS ROBERTO MENDES")));

    assertThat(provider().search(query("Carlos Eduardo Nunes"))).isEmpty();
  }

  /**
   * O sócio é consultado e o apontamento sai atribuído a ele. Sem a atribuição, o analista recebe
   * "sanção encontrada" sem saber se é a empresa ou um sócio — condutas diferentes.
   */
  @Test
  void socioNaListaGeraApontamentoAtribuidoAoSocio() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(ofac("SILVA, JOSE ANTONIO")));

    List<WatchlistEntry> hits =
        provider()
            .searchAll(
                List.of(
                    new WatchlistQuery("CNPJ", "11444777000161", "Acme Comercio Ltda"),
                    WatchlistQuery.of("CNPJ", ScreenedParty.socio("Jose Antonio da Silva"))));

    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().party().role()).isEqualTo(ScreenedParty.Role.SOCIO);
    assertThat(hits.getFirst().party().name()).isEqualTo("Jose Antonio da Silva");
  }

  /**
   * A base é carregada <b>uma vez</b> por screening, não uma por parte: com o {@code findNameEntries}
   * sendo um {@code findAll} da tabela inteira, uma PJ com 10 sócios faria 11 varreduras completas.
   */
  @Test
  void carregaABaseUmaVezSoParaTodasAsPartes() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(ofac("OSAMA BIN LADEN")));

    provider()
        .searchAll(
            List.of(
                new WatchlistQuery("CNPJ", "11444777000161", "Acme Comercio Ltda"),
                WatchlistQuery.of("CNPJ", ScreenedParty.socio("Joao da Silva")),
                WatchlistQuery.of("CNPJ", ScreenedParty.socio("Maria Souza")),
                WatchlistQuery.of("CNPJ", ScreenedParty.socio("Pedro Alves"))));

    // Uma única busca de candidatos para as quatro partes — e ela leva os tokens de TODAS.
    // Com o blocking por índice a propriedade ficou mais importante, não menos: uma ida ao banco
    // por sócio trocaria a memória economizada por latência de rede, N vezes por avaliação de PJ.
    var tokens = org.mockito.ArgumentCaptor.forClass(java.util.Set.class);
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1))
        .findNameCandidates(tokens.capture(), anyDouble());
    assertThat(tokens.getValue())
        .as("a busca precisa cobrir os tokens de todas as partes, senão um sócio fica sem candidato")
        .contains("ACME", "JOAO", "SILVA", "MARIA", "SOUZA", "PEDRO", "ALVES");
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
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(pep("JOAO DA SILVA", "111111")));

    // CPF consultado 529.982.247-25 -> centrais "982247", que não batem com "111111"
    List<WatchlistEntry> hits =
        provider().search(new WatchlistQuery("CPF", "52998224725", "Joao da Silva"));

    assertThat(hits).isEmpty();
  }

  @Test
  void cpfParcialCoincidenteConfirmaOMatchERegistraNaEvidencia() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(pep("JOAO DA SILVA", "982247")));

    List<WatchlistEntry> hits =
        provider().search(new WatchlistQuery("CPF", "52998224725", "Joao da Silva"));

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).type()).isEqualTo(MatchType.PEP);
    assertThat(hits.get(0).detail()).contains("CPF parcial confirmado");
  }

  /** Entrada com CPF parcial nunca é a mesma entidade que um CNPJ consultado. */
  @Test
  void consultaDeCnpjNaoCasaComEntradaDeCpfParcial() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(pep("ACME COMERCIO", "982247")));

    assertThat(provider().search(new WatchlistQuery("CNPJ", "11444777000161", "Acme Comercio")))
        .isEmpty();
  }

  /** Entradas sem discriminador (OFAC) seguem decidindo só pelo nome, como antes. */
  @Test
  void entradaSemCpfParcialSegueDecidindoApenasPeloNome() {
    when(repository.findNameCandidates(any(), anyDouble())).thenReturn(List.of(ofac("OSAMA BIN LADEN")));

    assertThat(provider().search(new WatchlistQuery("CPF", "52998224725", "Osama Bin Laden")))
        .hasSize(1);
  }
}
