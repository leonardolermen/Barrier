package com.barrier.riskengine.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * O payload precisa carregar o subject para a entrega poder ordenar por cliente.
 *
 * <p>Sem ele, {@code deliveries} só conhece o {@code assessmentId} — e dois eventos sobre o mesmo
 * cliente (a decisão e a mudança de nível de risco) teriam chaves diferentes, ou seja: nenhuma
 * ordem possível entre eles. O parceiro poderia receber "virou HIGH" antes de "virou MEDIUM".
 *
 * <p>Acréscimo retrocompatível, mesmo padrão de {@code identityReused}/{@code identityCheckedAt}: a
 * Webhook API repassa o payload como string opaca, sem desserializar em tipo estrito.
 */
class AssessmentCompletedPayloadTest {

  @Test
  void carregaOSubjectParaAOrdenacaoDaEntrega() {
    boolean temSubject =
        Arrays.stream(AssessmentCompletedPayload.class.getRecordComponents())
            .map(RecordComponent::getName)
            .anyMatch("subjectId"::equals);

    assertThat(temSubject)
        .as("sem subjectId no payload a entrega nao tem chave de ordenacao")
        .isTrue();
  }
}
