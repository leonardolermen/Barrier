package com.barrier.riskengine.history.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.history.domain.HistoryEventType;
import com.barrier.riskengine.history.domain.SubjectHistoryEvent;
import com.barrier.riskengine.history.repository.SubjectHistoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubjectHistoryServiceTest {

  @Mock SubjectHistoryRepository repository;

  @Test
  void recordSalvaEventoComOcorridoEmPadraoQuandoNaoInformado() {
    when(repository.save(any(SubjectHistoryEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    UUID subjectId = UUID.randomUUID();

    var service = new SubjectHistoryService(repository);
    SubjectHistoryEvent saved =
        service.record(subjectId, HistoryEventType.CHARGEBACK, "detalhe", null);

    assertThat(saved.subjectId()).isEqualTo(subjectId);
    assertThat(saved.eventType()).isEqualTo(HistoryEventType.CHARGEBACK);
    assertThat(saved.occurredAt()).isNotNull();
  }

  @Test
  void findBySubjectIdDelegaAoRepositorio() {
    UUID subjectId = UUID.randomUUID();
    var event =
        SubjectHistoryEvent.create(subjectId, HistoryEventType.FRAUD_REPORT, "x", Instant.now());
    when(repository.findBySubjectId(subjectId)).thenReturn(List.of(event));

    var service = new SubjectHistoryService(repository);

    assertThat(service.findBySubjectId(subjectId)).containsExactly(event);
  }
}
