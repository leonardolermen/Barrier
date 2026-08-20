package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.watchlist.interfaces.WatchlistSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Estado da última importação de cada fonte de watchlist.
 *
 * <p>Existe porque a importação falhava em silêncio: o {@link WatchlistImporter} isola a falha por
 * fonte e loga em ERROR, o que é correto — mas ninguém lê log de ERROR em tempo real, e a única
 * consequência visível era o screening passar a responder CLEAR para todo mundo. Aplicação
 * saudável, {@code /actuator/health} verde, e toda avaliação aprovada sem consulta a lista nenhuma.
 *
 * <p><b>Compartilhado entre réplicas (V046), antes era memória de instância.</b> O racional
 * anterior — "o que interessa é se <i>esta</i> instância tem cobertura utilizável" — não se
 * sustenta contra a arquitetura: {@code replaceSource} grava em {@code watchlist_entries}, que é
 * uma <b>tabela compartilhada</b>. A lista sempre foi global; só a medição dela era local. As
 * consequências de manter a medição local:
 *
 * <ul>
 *   <li><b>Bug que já existia, sem lock nenhum envolvido:</b> com 5 réplicas, se o download falha
 *       em uma delas (blip de rede), aquela réplica se dá por descoberta e a
 *       {@code ScreeningCoverageRiskRule} força REVIEW em tudo que ela atender — com a tabela
 *       integralmente populada pelas outras quatro. Um quinto do tráfego indo para revisão manual
 *       por erro de medição, não de dado.
 *   <li><b>Incompatível com o lock de job (V045):</b> com a importação virando singleton, quatro
 *       réplicas nunca importam. Em memória, essas quatro nasceriam com cobertura vazia e
 *       mandariam <b>100% das avaliações</b> para revisão. Por isso as duas mudanças são a mesma
 *       entrega: fazer o lock sozinho produziria um incidente pior que o problema que ele resolve.
 * </ul>
 *
 * <p>O que <b>não</b> mudou: falha preserva o último sucesso (a base ainda tem a versão anterior,
 * que segue utilizável até vencer por {@code barrier.watchlist.max-age}), e cobertura vencida não
 * conta — uma lista de sanções de seis meses atrás não cobre quem foi sancionado no mês passado.
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

  private static final RowMapper<SourceStatus> MAPPER =
      (rs, rowNum) -> {
        Timestamp lastSuccess = rs.getTimestamp("last_success_at");
        return new SourceStatus(
            rs.getString("source"),
            parseProvides(rs.getString("provides")),
            lastSuccess == null ? null : lastSuccess.toInstant(),
            rs.getInt("records"),
            rs.getString("last_error"));
      };

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration maxAge;

  public WatchlistImportStatus(
      JdbcTemplate jdbc, Clock clock, @Value("${barrier.watchlist.max-age:PT48H}") Duration maxAge) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.maxAge = maxAge;
  }

  void recordSuccess(WatchlistSource source, int records) {
    jdbc.update(
        """
        INSERT INTO watchlist_import_status
               (source, provides, last_success_at, records, last_error, updated_at)
        VALUES (?, ?, ?::timestamptz, ?, NULL, ?::timestamptz)
        ON CONFLICT (source) DO UPDATE
           SET provides        = EXCLUDED.provides,
               last_success_at = EXCLUDED.last_success_at,
               records         = EXCLUDED.records,
               last_error      = NULL,
               updated_at      = EXCLUDED.updated_at
        """,
        source.source(),
        formatProvides(source.provides()),
        Timestamp.from(Instant.now(clock)),
        records,
        Timestamp.from(Instant.now(clock)));
  }

  /**
   * Falha preserva {@code last_success_at} e {@code records} da linha existente — a base ainda tem
   * a versão anterior, que segue utilizável. {@code COALESCE} sobre a linha antiga é o que faz
   * isso; um {@code EXCLUDED} puro zeraria a cobertura a cada tentativa frustrada.
   */
  void recordFailure(WatchlistSource source, String error) {
    jdbc.update(
        """
        INSERT INTO watchlist_import_status
               (source, provides, last_success_at, records, last_error, updated_at)
        VALUES (?, ?, NULL, 0, ?, ?::timestamptz)
        ON CONFLICT (source) DO UPDATE
           SET provides   = EXCLUDED.provides,
               last_error = EXCLUDED.last_error,
               updated_at = EXCLUDED.updated_at
        """,
        source.source(),
        formatProvides(source.provides()),
        error,
        Timestamp.from(Instant.now(clock)));
  }

  public Collection<SourceStatus> all() {
    return jdbc.query("SELECT * FROM watchlist_import_status", MAPPER);
  }

  public Optional<SourceStatus> of(String source) {
    return jdbc
        .query("SELECT * FROM watchlist_import_status WHERE source = ?", MAPPER, source)
        .stream()
        .findFirst();
  }

  /**
   * Categorias efetivamente cobertas agora: só contam fontes que importaram com sucesso, trouxeram
   * pelo menos uma linha e não estão vencidas.
   */
  public Set<MatchType> coverage() {
    Instant now = Instant.now(clock);
    return all().stream()
        .filter(status -> status.usableAt(now, maxAge))
        .flatMap(status -> status.provides().stream())
        .collect(Collectors.toSet());
  }

  public Duration maxAge() {
    return maxAge;
  }

  private static String formatProvides(Set<MatchType> provides) {
    return provides.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
  }

  /**
   * Valor desconhecido é ignorado em vez de estourar: uma categoria removida do enum não pode
   * impedir a leitura da cobertura inteira — o efeito seria cobertura vazia, ou seja, tudo em
   * revisão manual.
   */
  private static Set<MatchType> parseProvides(String csv) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    List<String> names = Arrays.asList(csv.split(","));
    return names.stream()
        .map(String::trim)
        .filter(name -> !name.isEmpty())
        .map(
            name ->
                Arrays.stream(MatchType.values())
                    .filter(type -> type.name().equals(name))
                    .findFirst()
                    .orElse(null))
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());
  }
}
