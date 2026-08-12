package com.barrier.riskengine.screening.rule;

import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Regra: registros de inidoneidade/impedimento (CEIS/CNEP) viram apontamentos próprios.
 *
 * <p>Mesma forma de {@link SanctionMatchRule} e {@link PepMatchRule} — o que muda é a categoria, e
 * é ela que decide o peso lá no motor de risco.
 */
@Component
public class DebarmentMatchRule implements ScreeningRule {

  @Override
  public List<ScreeningHit> evaluate(ScreeningContext context) {
    return context.entries().stream()
        .filter(e -> e.type() == MatchType.DEBARMENT)
        .map(
            e ->
                new ScreeningHit(
                    MatchType.DEBARMENT,
                    e.basis(),
                    e.party(),
                    e.source(),
                    e.matchedName(),
                    e.detail()))
        .toList();
  }
}
