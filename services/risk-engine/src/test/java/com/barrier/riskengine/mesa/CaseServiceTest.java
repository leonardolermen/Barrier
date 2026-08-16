package com.barrier.riskengine.mesa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.mesa.domain.AssessmentCase;
import com.barrier.riskengine.mesa.domain.CaseAction;
import com.barrier.riskengine.mesa.domain.CaseActionType;
import com.barrier.riskengine.mesa.domain.CaseQueue;
import com.barrier.riskengine.mesa.repository.interfaces.AssessmentCaseRepository;
import com.barrier.riskengine.mesa.repository.interfaces.CaseActionRepository;
import com.barrier.riskengine.mesa.service.CaseService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** O ciclo de vida do caso e o escopo por tenant. */
class CaseServiceTest {

  private static final UUID CASO = UUID.randomUUID();
  private static final String TENANT = "acme";

  private final InMemoryCases cases = new InMemoryCases();
  private final InMemoryActions actions = new InMemoryActions();
  private final CaseService service = new CaseService(cases, actions);

  @Test
  void abre_o_caso_na_fila_e_e_idempotente() {
    AssessmentCase primeiro = service.open(CASO, TENANT, CaseQueue.ANALISE_PADRAO);
    AssessmentCase segundo = service.open(CASO, TENANT, CaseQueue.ALCADA_RISCO);

    assertThat(primeiro.openedAt()).isEqualTo(segundo.openedAt());
    assertThat(segundo.queue()).isEqualTo(CaseQueue.ANALISE_PADRAO);
    assertThat(cases.states).hasSize(1);
  }

  @Test
  void atribuir_registra_acao_e_grava_o_responsavel() {
    service.open(CASO, TENANT, CaseQueue.ANALISE_PADRAO);

    AssessmentCase caso = service.assign(CASO, TENANT, "ana@empresa");

    assertThat(caso.assignedTo()).isEqualTo("ana@empresa");
    assertThat(actions.appended).extracting(CaseAction::type).containsExactly(CaseActionType.ASSIGNED);
  }

  /** Pedir documento move a fila; só o recebimento transforma a espera em desconto de SLA. */
  @Test
  void ciclo_de_espera_move_a_fila_nos_dois_sentidos() {
    service.open(CASO, TENANT, CaseQueue.ANALISE_PADRAO);

    AssessmentCase esperando = service.requestDocument(CASO, TENANT, "ana@empresa", "comprovante");
    assertThat(esperando.queue()).isEqualTo(CaseQueue.AGUARDANDO_PARCEIRO);

    AssessmentCase devolvido = service.receiveDocument(CASO, TENANT, "ana@empresa", "comprovante");
    assertThat(devolvido.queue()).isEqualTo(CaseQueue.ANALISE_PADRAO);

    assertThat(actions.appended)
        .extracting(CaseAction::type)
        .containsExactly(CaseActionType.DOCUMENT_REQUESTED, CaseActionType.DOCUMENT_RECEIVED);
  }

  @Test
  void mover_registra_origem_e_destino_na_trilha() {
    service.open(CASO, TENANT, CaseQueue.ANALISE_PADRAO);

    service.move(CASO, TENANT, CaseQueue.ALCADA_RISCO, "ana@empresa");

    assertThat(actions.appended.get(0).detail()).isEqualTo("ANALISE_PADRAO -> ALCADA_RISCO");
  }

  @Test
  void fechar_encerra_o_caso_e_registra_a_decisao() {
    service.open(CASO, TENANT, CaseQueue.ANALISE_PADRAO);

    service.close(CASO, TENANT, "ana@empresa", "Aprovado em revisão");

    assertThat(service.find(CASO, TENANT)).get().extracting(AssessmentCase::isOpen).isEqualTo(false);
    assertThat(actions.appended).extracting(CaseAction::type).contains(CaseActionType.DECIDED);
  }

  /**
   * A decisão pode ser tomada por quem nunca abriu caso na mesa (avaliação decidida direto pelo
   * endpoint de decisão). Recusar aí bloquearia um fluxo que já existe e funciona.
   */
  @Test
  void fechar_caso_inexistente_nao_estoura() {
    service.close(UUID.randomUUID(), TENANT, "ana@empresa", "Aprovado");

    assertThat(actions.appended).isEmpty();
  }

  /** Caso de um parceiro não existe para outro. */
  @Test
  void caso_de_outro_tenant_nao_e_encontrado() {
    service.open(CASO, TENANT, CaseQueue.ANALISE_PADRAO);

    assertThat(service.find(CASO, "outro-tenant")).isEmpty();
    assertThatThrownBy(() -> service.assign(CASO, "outro-tenant", "invasor@empresa"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void fila_devolve_apenas_casos_abertos_do_tenant() {
    service.open(CASO, TENANT, CaseQueue.ANALISE_PADRAO);
    UUID outro = UUID.randomUUID();
    service.open(outro, TENANT, CaseQueue.ANALISE_PADRAO);
    service.close(outro, TENANT, "ana@empresa", "decidido");

    assertThat(service.queue(TENANT, CaseQueue.ANALISE_PADRAO, 10))
        .extracting(AssessmentCase::assessmentId)
        .containsExactly(CASO);
  }

  // --- dobras ----------------------------------------------------------------

  private static final class InMemoryCases implements AssessmentCaseRepository {
    private final Map<String, AssessmentCase> states = new HashMap<>();

    @Override
    public AssessmentCase save(AssessmentCase caso) {
      states.put(caso.assessmentId() + "|" + caso.tenantId(), caso);
      return caso;
    }

    @Override
    public Optional<AssessmentCase> find(UUID assessmentId, String tenantId) {
      return Optional.ofNullable(states.get(assessmentId + "|" + tenantId));
    }

    @Override
    public List<AssessmentCase> findOpenByQueue(String tenantId, CaseQueue queue, int limit) {
      return states.values().stream()
          .filter(c -> c.tenantId().equals(tenantId) && c.queue() == queue && c.isOpen())
          .sorted(java.util.Comparator.comparing(AssessmentCase::openedAt))
          .limit(limit)
          .toList();
    }
  }

  private static final class InMemoryActions implements CaseActionRepository {
    private final List<CaseAction> appended = new ArrayList<>();

    @Override
    public CaseAction append(CaseAction action) {
      appended.add(action);
      return action;
    }

    @Override
    public List<CaseAction> findByCase(UUID assessmentId, String tenantId) {
      return appended.stream()
          .filter(a -> a.assessmentId().equals(assessmentId) && a.tenantId().equals(tenantId))
          .toList();
    }
  }
}
