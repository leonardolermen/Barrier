package com.barrier.riskengine.rescreening.repository;

import com.barrier.riskengine.rescreening.domain.MonitoredSubject;
import com.barrier.riskengine.rescreening.repository.interfaces.MonitoredSubjectRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta por JDBC, não por JPA: o que se quer aqui é uma projeção de quatro colunas com os
 * tenants agregados, e obter isso por entidade significaria carregar {@code SubjectEntity} +
 * {@code TenantSubjectEntity} e montar o agrupamento em memória — o clássico N+1 no ponto do
 * sistema que varre a base inteira de clientes.
 */
@Repository
class MonitoredSubjectRepositoryImpl implements MonitoredSubjectRepository {

  /**
   * O {@code JOIN} com {@code tenant_subjects} é o filtro de "ativo": subject sem vínculo nenhum
   * não é cliente de ninguém e reavaliá-lo produziria decisão que ninguém pediu e ninguém recebe.
   */
  private static final String BY_DOCUMENTS =
      """
      SELECT s.id, s.document_type, s.document, s.name,
             string_agg(DISTINCT ts.tenant_id, ',' ORDER BY ts.tenant_id) AS tenant_ids
        FROM subjects s
        JOIN tenant_subjects ts ON ts.subject_id = s.id
       WHERE s.document = ANY (string_to_array(?, ','))
       GROUP BY s.id, s.document_type, s.document, s.name
      """;

  private static final String LINKED_PAGE =
      """
      SELECT s.id, s.document_type, s.document, s.name,
             string_agg(DISTINCT ts.tenant_id, ',' ORDER BY ts.tenant_id) AS tenant_ids
        FROM subjects s
        JOIN tenant_subjects ts ON ts.subject_id = s.id
       GROUP BY s.id, s.document_type, s.document, s.name
       ORDER BY s.id
       LIMIT ? OFFSET ?
      """;

  private final JdbcTemplate jdbc;

  MonitoredSubjectRepositoryImpl(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true)
  public List<MonitoredSubject> findByDocuments(Collection<String> documents) {
    if (documents.isEmpty()) {
      return List.of();
    }
    // Um parâmetro só, em vez de IN (?,?,...) montado por concatenação: o número de documentos vem
    // do tamanho do delta da lista, que não tem teto conhecido, e cada tamanho diferente geraria um
    // plano novo no banco. Documento é sempre dígitos, então a vírgula nunca é conteúdo.
    return jdbc.query(BY_DOCUMENTS, (rs, i) -> map(rs), String.join(",", documents));
  }

  @Override
  @Transactional(readOnly = true)
  public List<MonitoredSubject> findLinkedPage(int page, int size) {
    return jdbc.query(LINKED_PAGE, (rs, i) -> map(rs), size, (long) page * size);
  }

  private static MonitoredSubject map(java.sql.ResultSet rs) throws java.sql.SQLException {
    String tenants = rs.getString("tenant_ids");
    List<String> tenantIds =
        tenants == null || tenants.isBlank() ? List.of() : new ArrayList<>(List.of(tenants.split(",")));
    return new MonitoredSubject(
        UUID.fromString(rs.getString("id")),
        rs.getString("document_type"),
        rs.getString("document"),
        rs.getString("name"),
        tenantIds);
  }
}
