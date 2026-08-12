package com.barrier.riskengine.subject.repository;

import com.barrier.riskengine.subject.domain.Subject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.barrier.riskengine.subject.repository.interfaces.SubjectJpaRepository;
import com.barrier.riskengine.subject.repository.interfaces.SubjectRepository;
import com.barrier.riskengine.subject.repository.interfaces.TenantSubjectJpaRepository;
import org.springframework.stereotype.Repository;

@Repository
class SubjectRepositoryImpl implements SubjectRepository {

  private final SubjectJpaRepository subjects;
  private final TenantSubjectJpaRepository links;

  SubjectRepositoryImpl(SubjectJpaRepository subjects, TenantSubjectJpaRepository links) {
    this.subjects = subjects;
    this.links = links;
  }

  @Override
  public Subject save(Subject subject) {
    return toDomain(
        subjects.save(
            new SubjectEntity(
                subject.id(),
                subject.documentType(),
                subject.document(),
                subject.name(),
                subject.createdAt())));
  }

  @Override
  public Optional<Subject> findByDocument(String documentType, String document) {
    return subjects.findByDocumentTypeAndDocument(documentType, document).map(this::toDomain);
  }

  @Override
  public void link(String tenantId, UUID subjectId) {
    Instant now = Instant.now();
    links
        .findByTenantIdAndSubjectId(tenantId, subjectId)
        .ifPresentOrElse(
            existing -> {
              existing.touch(now);
              links.save(existing);
            },
            () -> links.save(new TenantSubjectEntity(UUID.randomUUID(), tenantId, subjectId, now)));
  }

  @Override
  public boolean isLinked(String tenantId, UUID subjectId) {
    return links.existsByTenantIdAndSubjectId(tenantId, subjectId);
  }

  private Subject toDomain(SubjectEntity e) {
    return new Subject(
        e.getId(), e.getDocumentType(), e.getDocument(), e.getName(), e.getCreatedAt());
  }
}
