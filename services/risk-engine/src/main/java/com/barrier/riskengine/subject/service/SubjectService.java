package com.barrier.riskengine.subject.service;

import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.domain.SubjectNotFoundException;
import com.barrier.riskengine.subject.repository.interfaces.SubjectRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso do subject: acha-ou-cria por documento (dedup), vincula ao tenant e consulta com
 * acesso por associação.
 */
@Service
public class SubjectService {

  private final SubjectRepository repository;

  public SubjectService(SubjectRepository repository) {
    this.repository = repository;
  }

  /** Retorna o subject do documento, criando-o se ainda não existir (dedup por documento). */
  @Transactional
  public Subject findOrCreate(String documentType, String document, String name) {
    return repository
        .findByDocument(documentType, document)
        .orElseGet(() -> create(documentType, document, name));
  }

  private Subject create(String documentType, String document, String name) {
    try {
      return repository.save(Subject.create(documentType, document, name));
    } catch (DataIntegrityViolationException e) {
      // corrida: outro POST criou o mesmo documento em paralelo (UNIQUE). Reaproveita.
      return repository
          .findByDocument(documentType, document)
          .orElseThrow(() -> e);
    }
  }

  /**
   * Busca o subject pelo id, <b>escopado por tenant</b> — mesma defesa de {@link #getForTenant}:
   * "nem {@code SubjectRepository} nem {@code SubjectService} têm assinatura que aceite só o
   * {@code subjectId}" (ver CLAUDE.md, seção Cadastro/ADR-0012) é invariante do projeto, não só do
   * cadastro CMN 4.753. Sem vínculo, trata como não encontrado.
   *
   * <p>Uso interno (o disparo de reavaliação por assurance, que já sabe {@code subjectId} e
   * {@code tenantId} de um {@code AssuranceCheck} e precisa só de documento/nome para montar a
   * reavaliação) — não é o caminho de consulta do parceiro, que segue {@link #getForTenant} por
   * documento.
   */
  @Transactional(readOnly = true)
  public Subject findById(UUID subjectId, String tenantId) {
    return repository
        .findById(subjectId)
        .filter(s -> repository.isLinked(tenantId, s.id()))
        .orElseThrow(() -> new SubjectNotFoundException("Subject não encontrado: " + subjectId));
  }

  /** Garante a visibilidade do subject para o tenant. */
  @Transactional
  public void link(String tenantId, UUID subjectId) {
    repository.link(tenantId, subjectId);
  }

  /**
   * Consulta um subject <b>no escopo do tenant</b>: sem vínculo, responde como não encontrado —
   * a empresa 2 não descobre um cliente que só a empresa 1 tem.
   */
  @Transactional(readOnly = true)
  public Subject getForTenant(String tenantId, String documentType, String document) {
    Subject subject =
        repository
            .findByDocument(documentType, document)
            .filter(s -> repository.isLinked(tenantId, s.id()))
            .orElseThrow(() -> new SubjectNotFoundException("Cliente não encontrado"));
    return subject;
  }
}
