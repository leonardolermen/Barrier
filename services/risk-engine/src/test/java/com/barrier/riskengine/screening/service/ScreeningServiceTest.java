package com.barrier.riskengine.screening.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.interfaces.WatchlistProvider;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.enums.MatchBasis;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.ScreenedParty;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.domain.enums.ScreeningStatus;
import com.barrier.riskengine.screening.repository.interfaces.ScreeningResultRepository;
import com.barrier.riskengine.screening.repository.interfaces.WatchlistEntryRepository;
import com.barrier.riskengine.screening.rule.PepMatchRule;
import com.barrier.riskengine.screening.rule.SanctionMatchRule;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScreeningServiceTest {

  @Mock WatchlistProvider provider;
  @Mock ScreeningResultRepository repository;
  @Mock
  WatchlistEntryRepository watchlistEntries;

  private ScreeningService service;

  @BeforeEach
  void setUp() {
    when(repository.save(any(ScreeningResult.class))).thenAnswer(inv -> inv.getArgument(0));
    service =
        new ScreeningService(
            List.of(provider),
            List.of(new PepMatchRule(), new SanctionMatchRule()),
            repository,
            watchlistEntries);
  }

  private static final ScreenedParty TITULAR = ScreenedParty.titular("Fulano", "11144477735");

  private ScreeningCommand command() {
    return new ScreeningCommand("aid", "CPF", "11144477735", "Fulano");
  }

  /**
   * Regressão do buraco mais barato de contornar do sistema: o screening consultava só o titular,
   * então uma PJ com situação ATIVA e um sócio na SDN saía aprovada — o sócio nunca era perguntado.
   */
  @Test
  void consultaOTitularEAsPartesRelacionadas() {
    when(provider.searchAll(any())).thenReturn(List.of());

    service.screen(
        new ScreeningCommand(
            "aid",
            "CNPJ",
            "11444777000161",
            "Acme Ltda",
            List.of(
                ScreenedParty.socio("Joao da Silva"),
                ScreenedParty.representanteLegal("Maria Souza", "52998224725"))));

    ArgumentCaptor<List<WatchlistQuery>> captor = ArgumentCaptor.captor();
    verify(provider).searchAll(captor.capture());
    assertThat(captor.getValue())
        .extracting(q -> q.party().role())
        .containsExactly(
            ScreenedParty.Role.TITULAR,
            ScreenedParty.Role.SOCIO,
            ScreenedParty.Role.REPRESENTANTE_LEGAL);
  }

  /**
   * Uma chamada só ao provider, com todas as partes. Um {@code search} por parte multiplicaria o
   * custo de uma PJ pelo tamanho do quadro societário — e o provider fuzzy varre a base inteira.
   */
  @Test
  void consultaOProviderUmaVezComTodasAsPartes() {
    when(provider.searchAll(any())).thenReturn(List.of());

    service.screen(
        new ScreeningCommand(
            "aid",
            "CNPJ",
            "11444777000161",
            "Acme Ltda",
            List.of(ScreenedParty.socio("A"), ScreenedParty.socio("B"), ScreenedParty.socio("C"))));

    verify(provider, org.mockito.Mockito.times(1)).searchAll(any());
  }

  /** Parte sem nome utilizável não vira consulta — o QSA às vezes traz linha vazia. */
  @Test
  void parteSemNomeNaoViraConsulta() {
    when(provider.searchAll(any())).thenReturn(List.of());

    service.screen(
        new ScreeningCommand(
            "aid", "CNPJ", "11444777000161", "Acme Ltda", List.of(ScreenedParty.socio("  "))));

    ArgumentCaptor<List<WatchlistQuery>> captor = ArgumentCaptor.captor();
    verify(provider).searchAll(captor.capture());
    assertThat(captor.getValue()).hasSize(1);
  }

  @Test
  void semRegistrosResultaClear() {
    when(provider.searchAll(any())).thenReturn(List.of());

    ScreeningResult result = service.screen(command());

    assertThat(result.status()).isEqualTo(ScreeningStatus.CLEAR);
    assertThat(result.hits()).isEmpty();
  }

  /**
   * Um CLEAR sem saber contra qual lista é uma afirmação sem lastro: a base é substituída todo dia
   * ({@code replaceSource}), então em seis meses ninguém consegue dizer se o nome estava lá
   * <b>naquele dia</b>.
   */
  @Test
  void registraContraQualVersaoDeCadaListaOScreeningRodou() {
    when(provider.searchAll(any())).thenReturn(List.of());
    when(watchlistEntries.sourceVersions())
        .thenReturn(java.util.Map.of("OFAC", "2026-08-10", "CEIS", "20260809"));

    ScreeningResult result = service.screen(command());

    assertThat(result.sources()).containsEntry("OFAC", "2026-08-10").containsEntry("CEIS", "20260809");
  }

  @Test
  void registroDeSancaoResultaHit() {
    when(provider.searchAll(any()))
        .thenReturn(
            List.of(new WatchlistEntry(MatchType.SANCTION, MatchBasis.DOCUMENT, TITULAR, "OFAC", "Empresa X", "SDN")));

    ScreeningResult result = service.screen(command());

    assertThat(result.status()).isEqualTo(ScreeningStatus.HIT);
    assertThat(result.hits()).hasSize(1);
    assertThat(result.hits().get(0).type()).isEqualTo(MatchType.SANCTION);
  }

  @Test
  void pepESancaoGeramDoisApontamentos() {
    when(provider.searchAll(any()))
        .thenReturn(
            List.of(
                new WatchlistEntry(MatchType.PEP, MatchBasis.NAME, TITULAR, "base-pep", "Fulano", "cargo"),
                new WatchlistEntry(MatchType.SANCTION, MatchBasis.DOCUMENT, TITULAR, "ONU", "Fulano", "lista")));

    ScreeningResult result = service.screen(command());

    assertThat(result.hits()).hasSize(2);
    assertThat(result.hasHits()).isTrue();
  }
}
