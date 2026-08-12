package com.barrier.riskengine.rescreening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentOrigin;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import com.barrier.riskengine.rescreening.domain.MonitoredSubject;
import com.barrier.riskengine.rescreening.repository.interfaces.MonitoredSubjectRepository;
import com.barrier.riskengine.rescreening.service.RescreeningService;
import com.barrier.riskengine.screening.domain.WatchlistDelta;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RescreeningServiceTest {

  private static final String CPF = "11144477735";

  @Mock MonitoredSubjectRepository subjects;
  @Mock AssessmentService assessments;

  private RescreeningService service() {
    return service(true, 500);
  }

  private RescreeningService service(boolean enabled, int teto) {
    return new RescreeningService(subjects, assessments, enabled, teto, 0.90, 6);
  }

  private static MonitoredSubject subject(String nome, String... tenants) {
    return new MonitoredSubject(UUID.randomUUID(), "CPF", CPF, nome, List.of(tenants));
  }

  private static WatchlistDelta delta(WatchlistRecord... records) {
    return WatchlistDelta.of(List.of(records));
  }

  private static WatchlistRecord porDocumento(String documento) {
    return new WatchlistRecord("CEIS", MatchType.DEBARMENT, documento, "FULANO DE TAL", "d");
  }

  private static WatchlistRecord porNome(String nome) {
    return new WatchlistRecord("OFAC", MatchType.SANCTION, null, nome, "SDN");
  }

  @Test
  void clienteQueEntrouNaListaPorDocumentoEReavaliado() {
    when(subjects.findByDocuments(java.util.Set.of(CPF))).thenReturn(List.of(subject("Fulano de Tal", "t1")));

    int criadas = service().onImported("CEIS", "v2", delta(porDocumento(CPF)));

    assertThat(criadas).isEqualTo(1);
    ArgumentCaptor<SubmitAssessmentCommand> cmd =
        ArgumentCaptor.forClass(SubmitAssessmentCommand.class);
    verify(assessments).submit(cmd.capture());
    assertThat(cmd.getValue().origin()).isEqualTo(AssessmentOrigin.RESCREENING);
    assertThat(cmd.getValue().originDetail()).isEqualTo("CEIS@v2");
    assertThat(cmd.getValue().tenantId()).isEqualTo("t1");
    // Sem chave de idempotência: reaproveitar a decisão anterior devolveria exatamente o resultado
    // tomado antes de o cliente estar na lista.
    assertThat(cmd.getValue().hasIdempotencyKey()).isFalse();
  }

  /**
   * OFAC e CSNU publicam nome sem documento — são justamente as listas de sanção financeira. Sem o
   * caminho por nome, o monitoramento cobriria só inidoneidade e ignoraria a obrigação legal.
   */
  @Test
  void clienteQueEntrouNaListaSoPorNomeEReavaliado() {
    when(subjects.findLinkedPage(anyInt(), anyInt()))
        .thenReturn(List.of(subject("Jose Antonio da Silva", "t1")))
        .thenReturn(List.of());

    int criadas = service().onImported("OFAC", "2026-08-12", delta(porNome("SILVA, JOSE ANTONIO")));

    assertThat(criadas).isEqualTo(1);
  }

  /** Homônimo parcial não basta: o limiar é o mesmo do screening da avaliação. */
  @Test
  void nomeQueNaoCasaNaoGeraReavaliacao() {
    when(subjects.findLinkedPage(anyInt(), anyInt()))
        .thenReturn(List.of(subject("Carlos Eduardo Nunes", "t1")))
        .thenReturn(List.of());

    int criadas = service().onImported("OFAC", "2026-08-12", delta(porNome("CARLOS ROBERTO MENDES")));

    assertThat(criadas).isZero();
    verify(assessments, never()).submit(any());
  }

  /**
   * O subject é global, a decisão é por tenant: reavaliar uma vez só entregaria o resultado a um
   * parceiro e deixaria os outros com uma decisão que a lista já contradiz.
   */
  @Test
  void umaReavaliacaoPorTenantVinculado() {
    when(subjects.findByDocuments(java.util.Set.of(CPF)))
        .thenReturn(List.of(subject("Fulano de Tal", "t1", "t2")));

    int criadas = service().onImported("CEIS", "v2", delta(porDocumento(CPF)));

    assertThat(criadas).isEqualTo(2);
  }

  /** Cinco entradas novas casando com o mesmo cliente é um caso para o analista, não cinco. */
  @Test
  void clienteAfetadoPorDuasEntradasGeraUmaAvaliacaoSo() {
    MonitoredSubject fulano = subject("Fulano de Tal", "t1");
    when(subjects.findByDocuments(any())).thenReturn(List.of(fulano));
    when(subjects.findLinkedPage(anyInt(), anyInt())).thenReturn(List.of(fulano)).thenReturn(List.of());

    int criadas =
        service().onImported("CEIS", "v2", delta(porDocumento(CPF), porNome("FULANO DE TAL")));

    assertThat(criadas).isEqualTo(1);
  }

  /**
   * Primeira carga de uma fonte: tudo é "novo" sem que nada tenha acontecido no mundo. Disparar
   * aqui reavaliaria a base inteira de clientes — cada uma com consulta de bureau paga.
   */
  @Test
  void linhaDeBaseNaoDisparaNada() {
    int criadas = service().onImported("OFAC", "2026-08-12", WatchlistDelta.firstLoad());

    assertThat(criadas).isZero();
    verify(subjects, never()).findByDocuments(any());
    verify(subjects, never()).findLinkedPage(anyInt(), anyInt());
  }

  /**
   * Delta gigante é sinal de fonte que mudou de layout, não de sanção em massa. Para e grita, em
   * vez de seguir em silêncio queimando uma consulta de bureau por cliente.
   */
  @Test
  void acimaDoTetoAbortaSemCriarNenhuma() {
    when(subjects.findByDocuments(any()))
        .thenReturn(List.of(subject("Fulano de Tal", "t1"), subject("Beltrano", "t1")));

    int criadas = service(true, 1).onImported("CEIS", "v2", delta(porDocumento(CPF)));

    assertThat(criadas).isZero();
    verify(assessments, never()).submit(any());
  }

  /** Falha em um cliente não pode interromper o monitoramento dos demais. */
  @Test
  void falhaEmUmClienteNaoImpedeOsOutros() {
    when(subjects.findByDocuments(any()))
        .thenReturn(List.of(subject("Fulano de Tal", "t1"), subject("Beltrano de Tal", "t2")));
    when(assessments.submit(any()))
        .thenThrow(new RuntimeException("bureau fora"))
        .thenReturn(null);

    int criadas = service().onImported("CEIS", "v2", delta(porDocumento(CPF)));

    assertThat(criadas).isEqualTo(1);
  }

  @Test
  void desligadoNaoTocaNaBase() {
    int criadas = service(false, 500).onImported("CEIS", "v2", delta(porDocumento(CPF)));

    assertThat(criadas).isZero();
    verify(subjects, never()).findByDocuments(any());
  }
}
