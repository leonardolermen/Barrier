package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Expõe a cobertura de listas restritivas em {@code /actuator/health}.
 *
 * <p>Sem isto, o modo de falha mais perigoso do serviço era invisível: importação falha, tabela
 * fica vazia, screening responde CLEAR, todo mundo é aprovado — e o health continua verde, porque
 * ele só olhava banco e disco. Aqui a saúde passa a refletir o que o serviço se propõe a fazer, e
 * não só se os recursos técnicos respondem.
 *
 * <p>Fica DOWN quando falta cobertura de sanções ou de PEP, o que tira a instância do balanceador
 * em vez de deixá-la decidindo sem insumo.
 */
@Component
public class WatchlistHealthIndicator implements HealthIndicator {

  private static final Set<MatchType> REQUIRED = Set.of(MatchType.SANCTION, MatchType.PEP);

  private final WatchlistImportStatus status;

  public WatchlistHealthIndicator(WatchlistImportStatus status) {
    this.status = status;
  }

  @Override
  public Health health() {
    Set<MatchType> covered = status.coverage();
    Set<MatchType> missing =
        REQUIRED.stream().filter(t -> !covered.contains(t)).collect(java.util.stream.Collectors.toSet());

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("coberturaExigida", REQUIRED);
    details.put("coberturaAtual", covered);
    details.put("idadeMaxima", status.maxAge().toString());
    for (WatchlistImportStatus.SourceStatus source : status.all()) {
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("ultimoSucesso", source.lastSuccessAt());
      detail.put("registros", source.records());
      if (source.lastError() != null) {
        detail.put("ultimoErro", source.lastError());
      }
      details.put("fonte:" + source.source(), detail);
    }

    Health.Builder builder = missing.isEmpty() ? Health.up() : Health.down();
    if (!missing.isEmpty()) {
      details.put("coberturaFaltante", missing);
    }
    return builder.withDetails(details).build();
  }
}
