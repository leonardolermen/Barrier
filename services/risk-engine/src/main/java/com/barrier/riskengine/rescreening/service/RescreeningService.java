package com.barrier.riskengine.rescreening.service;

import com.barrier.commons.name.NameNormalizer;
import com.barrier.commons.name.NameTokens;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import com.barrier.riskengine.rescreening.domain.MonitoredSubject;
import com.barrier.riskengine.rescreening.repository.interfaces.MonitoredSubjectRepository;
import com.barrier.riskengine.screening.domain.WatchlistDelta;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.watchlist.WatchlistImportListener;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Monitoramento contínuo: reavalia clientes quando uma lista restritiva passa a apontá-los
 * (Circular 3.978).
 *
 * <p>O motor decidia uma vez, no onboarding. Cliente aprovado em janeiro e sancionado em março
 * seguia aprovado para sempre — não é uma falha que apareça em log ou métrica, é uma verificação
 * que simplesmente nunca acontece. Aqui o gatilho é o <b>delta</b> de cada importação: quem entrou
 * na lista desde a carga anterior.
 *
 * <p>Reavaliar é <b>submeter uma avaliação nova</b>, pelo mesmo pipeline do onboarding
 * ({@code AssessmentService.submit}), com {@code origin = RESCREENING}. Deliberado: um caminho
 * paralelo que só refizesse o screening decidiria diferente do onboarding sobre o mesmo cliente —
 * mesma lista, mesmas regras, respostas divergentes conforme quem perguntou — e não produziria
 * decisão, evento nem webhook. O parceiro fica sabendo pelo mesmo canal de sempre.
 *
 * <p>Três travas contra transformar o monitoramento numa avalanche:
 *
 * <ul>
 *   <li><b>linha de base</b> — importação sobre fonte vazia não dispara nada (ver
 *       {@link WatchlistDelta}). Sem isso, subir o sistema pela primeira vez, ou ligar uma fonte
 *       nova, reavaliaria a base inteira de clientes de uma vez;
 *   <li><b>teto por importação</b> — acima dele o rescreening para e <b>grita</b>. O que não se faz
 *       é seguir em silêncio: um delta gigante é uma fonte que mudou de layout, e reavaliar a base
 *       inteira contra dados suspeitos custa uma consulta de bureau paga por cliente;
 *   <li><b>uma avaliação por (subject, tenant) por importação</b> — o mesmo cliente casando com
 *       cinco entradas novas é um cliente para o analista olhar, não cinco.
 * </ul>
 */
@Service
public class RescreeningService implements WatchlistImportListener {

  private static final Logger log = LoggerFactory.getLogger(RescreeningService.class);

  private static final int PAGE_SIZE = 500;

  private final MonitoredSubjectRepository subjects;
  private final AssessmentService assessments;
  private final boolean enabled;
  private final int maxSubjectsPerImport;
  private final double threshold;
  private final int minNameLength;

  public RescreeningService(
      MonitoredSubjectRepository subjects,
      AssessmentService assessments,
      @Value("${barrier.rescreening.enabled:true}") boolean enabled,
      @Value("${barrier.rescreening.max-subjects-per-import:500}") int maxSubjectsPerImport,
      // Mesmo limiar e mesmo piso de tamanho do screening por nome. Divergir aqui produziria a
      // pior combinação possível: um cliente que o rescreening levanta e o screening da avaliação
      // resultante não confirma, ou o contrário — apontamento que só aparece para quem re-analisa.
      @Value("${barrier.screening.fuzzy.threshold:0.90}") double threshold,
      @Value("${barrier.screening.fuzzy.min-name-length:6}") int minNameLength) {
    this.subjects = subjects;
    this.assessments = assessments;
    this.enabled = enabled;
    this.maxSubjectsPerImport = maxSubjectsPerImport;
    this.threshold = threshold;
    this.minNameLength = minNameLength;
  }

  @Override
  public void onImported(String source, String listVersion, WatchlistDelta delta) {
    rescreen(source, listVersion, delta);
  }

  /**
   * Reage ao resultado de uma importação. Nunca lança: o rescreening é consequência da importação,
   * e derrubá-la por falha aqui trocaria "não reavaliei" por "a lista também não atualizou".
   *
   * <p>Mesmo corpo do {@link #onImported}, mas devolvendo a contagem — a interface de listener não
   * tem por que conhecer quantas avaliações saíram, e os testes precisam desse número.
   *
   * @return quantas avaliações foram criadas
   */
  public int rescreen(String source, String listVersion, WatchlistDelta delta) {
    if (!enabled) {
      return 0;
    }
    if (delta.baseline()) {
      log.info("Rescreening: {} é linha de base (base vazia antes), nada a reavaliar", source);
      return 0;
    }
    if (delta.isEmpty()) {
      return 0;
    }
    try {
      return rescreen(source, listVersion, delta.added());
    } catch (RuntimeException e) {
      log.error("Rescreening de {} falhou; a importação da lista permanece válida", source, e);
      return 0;
    }
  }

  private int rescreen(String source, String listVersion, List<WatchlistRecord> added) {
    Map<UUID, MonitoredSubject> affected = new LinkedHashMap<>();
    byDocument(added).forEach(s -> affected.put(s.id(), s));
    byName(added, affected);

    if (affected.isEmpty()) {
      log.info(
          "Rescreening {}: {} entrada(s) nova(s), nenhum cliente afetado", source, added.size());
      return 0;
    }
    if (affected.size() > maxSubjectsPerImport) {
      log.error(
          "Rescreening {} abortado: {} cliente(s) afetado(s) acima do teto de {}."
              + " Delta desse tamanho é sinal de fonte que mudou de layout, não de sanção em massa."
              + " Nenhuma reavaliação foi criada; investigue a importação e reprocesse.",
          source,
          affected.size(),
          maxSubjectsPerImport);
      return 0;
    }

    String originDetail = source + "@" + listVersion;
    int created = 0;
    for (MonitoredSubject subject : affected.values()) {
      for (String tenantId : subject.tenantIds()) {
        created += submit(subject, tenantId, originDetail) ? 1 : 0;
      }
    }
    log.warn(
        "Rescreening {}: {} entrada(s) nova(s) → {} cliente(s) afetado(s), {} reavaliação(ões)"
            + " criada(s) (lista {})",
        source,
        added.size(),
        affected.size(),
        created,
        listVersion);
    return created;
  }

  /** Falha de um cliente não interrompe os demais: o resto da lista continua sendo monitorado. */
  private boolean submit(MonitoredSubject subject, String tenantId, String originDetail) {
    try {
      assessments.submit(
          SubmitAssessmentCommand.rescreening(
              tenantId,
              DocumentType.valueOf(subject.documentType()),
              subject.document(),
              subject.name(),
              originDetail));
      return true;
    } catch (RuntimeException e) {
      log.error(
          "Rescreening: falha ao reavaliar o subject {} para o tenant {} ({})",
          subject.id(),
          tenantId,
          originDetail,
          e);
      return false;
    }
  }

  /** Caminho exato, para as listas que publicam CPF/CNPJ (CEIS/CNEP e parte da OFAC). */
  private List<MonitoredSubject> byDocument(List<WatchlistRecord> added) {
    Set<String> documents =
        added.stream()
            .map(WatchlistRecord::document)
            .filter(Objects::nonNull)
            .filter(d -> !d.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    return documents.isEmpty() ? List.of() : subjects.findByDocuments(documents);
  }

  /**
   * Caminho por nome, para as listas que não publicam documento — que são justamente as de sanção
   * (OFAC e CSNU). Sem ele, o monitoramento contínuo cobriria só inidoneidade e ignoraria sanção
   * financeira, que é a obrigação legal direta.
   *
   * <p>Custo: entradas novas × clientes. É a mesma varredura sem índice que o screening já faz por
   * avaliação, aqui aplicada uma vez por importação em vez de uma vez por cliente. A base de
   * clientes é percorrida em páginas, e as entradas novas são tokenizadas uma única vez.
   */
  private void byName(List<WatchlistRecord> added, Map<UUID, MonitoredSubject> affected) {
    List<NameTokens> entries =
        added.stream()
            .map(WatchlistRecord::name)
            .filter(Objects::nonNull)
            .filter(n -> NameNormalizer.normalize(n).length() >= minNameLength)
            .map(NameTokens::of)
            .filter(t -> !t.isEmpty())
            .toList();
    if (entries.isEmpty()) {
      return;
    }

    for (int page = 0; ; page++) {
      List<MonitoredSubject> batch = subjects.findLinkedPage(page, PAGE_SIZE);
      if (batch.isEmpty()) {
        return;
      }
      for (MonitoredSubject subject : batch) {
        if (affected.containsKey(subject.id())) {
          continue;
        }
        if (matchesAny(subject, entries)) {
          affected.put(subject.id(), subject);
        }
      }
      if (batch.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  /**
   * Cobertura simétrica, igual à do screening por nome: a lista publica {@code SOBRENOME, Nome} e o
   * cadastro publica o nome direto, então exigir uma direção específica é o mesmo que não procurar.
   */
  private boolean matchesAny(MonitoredSubject subject, List<NameTokens> entries) {
    if (NameNormalizer.normalize(subject.name()).length() < minNameLength) {
      return false;
    }
    NameTokens subjectTokens = NameTokens.of(subject.name());
    if (subjectTokens.isEmpty()) {
      return false;
    }
    for (NameTokens entry : entries) {
      if (subjectTokens.coveredBy(entry, threshold) || entry.coveredBy(subjectTokens, threshold)) {
        return true;
      }
    }
    return false;
  }
}
