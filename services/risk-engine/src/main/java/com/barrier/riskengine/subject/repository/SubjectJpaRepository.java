package com.barrier.riskengine.subject.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SubjectJpaRepository extends JpaRepository<SubjectEntity, UUID> {

  Optional<SubjectEntity> findByDocumentTypeAndDocument(String documentType, String document);
}
