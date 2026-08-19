package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.commons.jobs.SingletonJobLock;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistDelta;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.repository.interfaces.WatchlistEntryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import com.barrier.riskengine.screening.watchlist.interfaces.WatchlistSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WatchlistImporterTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-08-07T10:00:00Z"), ZoneOffset.UTC);

  @Mock
  WatchlistSource source;
  @Mock WatchlistEntryRepository repository;
  @Mock WatchlistImportListener listener;

  @Mock WatchlistImportStatus status;
  @Mock SingletonJobLock jobLock;

  private WatchlistImporter importer() {
    return new WatchlistImporter(
        List.of(source), repository, status, List.of(listener), jobLock);
  }

  private WatchlistBatch batchCom(int registros) {
    List<WatchlistRecord> records =
        java.util.stream.IntStream.range(0, registros)
            .mapToObj(i -> new WatchlistRecord("CEIS", MatchType.SANCTION, "1", "N" + i, "d"))
            .toList();
    return new WatchlistBatch("v1", records);
  }

  @Test
  void importaSubstituindoAFonteERegistraSucesso() {
    when(source.source()).thenReturn("CEIS");
    when(source.fetch()).thenReturn(batchCom(2));
    when(repository.replaceSource(eq("CEIS"), eq("v1"), anyList())).thenReturn(WatchlistDelta.firstLoad());

    importer().importAll();

    verify(repository).replaceSource(eq("CEIS"), eq("v1"), anyList());
    verify(status).recordSuccess(source, 2);
  }

  @Test
  void falhaDeUmaFonteNaoLancaNemGrava() {
    when(source.source()).thenReturn("CEIS");
    when(source.fetch()).thenThrow(new RuntimeException("download falhou"));

    importer().importAll();

    verify(repository, never()).replaceSource(eq("CEIS"), org.mockito.ArgumentMatchers.any(), anyList());
    verify(status).recordFailure(eq(source), contains("download falhou"));
  }

  /**
   * Um CSV com layout novo, ou um ZIP truncado, parseia para zero linhas sem lançar. Substituir a
   * base por vazio apagaria a lista de sanções inteira e o screening passaria a responder CLEAR
   * para todos — falha silenciosa com efeito de aprovar todo mundo.
   */
  @Test
  void importacaoVaziaNaoSubstituiABaseEContaComoFalha() {
    when(source.source()).thenReturn("CEIS");
    when(source.fetch()).thenReturn(new WatchlistBatch("v2", List.of()));

    importer().importAll();

    verify(repository, never()).replaceSource(eq("CEIS"), org.mockito.ArgumentMatchers.any(), anyList());
    verify(status).recordFailure(eq(source), contains("0 registros"));
  }

  // A semântica de cobertura (falha preserva o sucesso anterior, importação vencida não conta)
  // saiu daqui para WatchlistCoverageSharedIntegrationTest quando o status virou tabela: passou a
  // depender do banco, e o que este teste cobre é o comportamento do IMPORTADOR.

  /**
   * O delta é o gatilho do monitoramento contínuo, e chega ao rescreening com a fonte e a versão
   * que o produziram — sem isso a reavaliação não sabe dizer por que existe.
   */
  @Test
  void repassaODeltaParaOMonitoramentoContinuo() {
    WatchlistDelta delta =
        WatchlistDelta.of(
            List.of(new WatchlistRecord("CEIS", MatchType.SANCTION, "1", "NOVO", "d")));
    when(source.source()).thenReturn("CEIS");
    when(source.fetch()).thenReturn(batchCom(2));
    when(repository.replaceSource(eq("CEIS"), eq("v1"), anyList())).thenReturn(delta);

    importer().importAll();

    verify(listener).onImported("CEIS", "v1", delta);
  }

  /** Rescreening é consequência da importação, não condição: falha dele não desfaz a lista. */
  @Test
  void falhaDoRescreeningNaoInvalidaAImportacao() {
    when(source.source()).thenReturn("CEIS");
    when(source.fetch()).thenReturn(batchCom(2));
    when(repository.replaceSource(eq("CEIS"), eq("v1"), anyList())).thenReturn(WatchlistDelta.firstLoad());
    org.mockito.Mockito.doThrow(new RuntimeException("banco fora"))
        .when(listener)
        .onImported(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

    importer().importAll();

    verify(status).recordSuccess(source, 2);
    verify(status, never()).recordFailure(eq(source), org.mockito.ArgumentMatchers.any());
  }
}
