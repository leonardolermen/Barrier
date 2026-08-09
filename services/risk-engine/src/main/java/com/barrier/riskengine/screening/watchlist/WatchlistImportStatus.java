package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.MatchType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Estado da última importação de cada fonte de watchlist.
 *
 * <p>Existe porque a importação falhava em silêncio: o {@link WatchlistImporter} isola a falha por
 * fonte e loga em ERROR, o que é correto — mas ninguém lê log de ERROR em tempo real, e a única
 * consequência visível era o screening passar a responder CLEAR para todo mundo. Aplicação
 * saudável, {@code /actuator/health} verde, e toda avaliação aprovada sem consulta a lista
 * nenhuma.
 *
 * <p>Guardar isto em memória (e não em tabela) é deliberado: o que interessa é se <b>esta
 * instância</b> tem cobertura utilizável agora. Uma réplica que subiu e não conseguiu importar não
 * pode se dar por coberta porque outra réplica conseguiu.
 */
@Component
public class WatchlistImportStatus {

  /**
   * Resultado da última tentativa de importar uma fonte.
   *
   * @param source nome da fonte
   * @param provides categorias que a fonte cobre quando a importação dá certo
   * @param lastSuccessAt instante da última importação bem-sucedida; {@code null} se nunca houve
   * @param records quantidade importada na última vez que deu certo
   * @param lastError motivo da última falha; {@code null} se a última tentativa deu certo
   */
  public record SourceStatus(
      String source,
      Set<MatchType> provides,
      Instant lastSuccessAt,
      int records,
      String lastError) {

    boolean usableAt(Instant now, Duration maxAge) {
      return lastSuccessAt != null && records > 0 && !lastSuccessAt.plus(maxAge).isBefore(now);
    }
  }

  private final Map<String, SourceStatus> bySource = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Duration maxAge;

  public WatchlistImportStatus(
      Clock clock, @Value("${barrier.watchlist.max-age:PT48H}") Duration maxAge) {
    this.clock = clock;
    this.maxAge = maxAge;
  }

  void recordSuccess(WatchlistSource source, int records) {
    bySource.put(
        source.source(),
        new SourceStatus(
            source.source(), source.provides(), Instant.now(clock), records, null));
  }

  /** Falha preserva o último sucesso: a base ainda tem a versão anterior, que segue utilizável. */
  void recordFailure(WatchlistSource source, String error) {
    bySource.compute(
        source.source(),
        (name, previous) ->
            new SourceStatus(
                name,
                source.provides(),
                previous == null ? null : previous.lastSuccessAt(),
                previous == null ? 0 : previous.records(),
                error));
  }

  public Collection<SourceStatus> all() {
    return bySource.values();
  }

  public Optional<SourceStatus> of(String source) {
    return Optional.ofNullable(bySource.get(source));
  }

  /**
   * Categorias efetivamente cobertas agora: só contam fontes que importaram com sucesso, trouxeram
   * pelo menos uma linha e não estão vencidas. Uma lista de sanções de seis meses atrás não cobre
   * quem foi sancionado no mês passado.
   */
  public Set<MatchType> coverage() {
    Instant now = Instant.now(clock);
    return bySource.values().stream()
        .filter(status -> status.usableAt(now, maxAge))
        .flatMap(status -> status.provides().stream())
        .collect(Collectors.toSet());
  }

  public Duration maxAge() {
    return maxAge;
  }
}
