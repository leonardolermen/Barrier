package com.barrier.riskengine.subject.repository.interfaces;

import java.util.Optional;
import java.util.UUID;

import com.barrier.riskengine.subject.repository.SubjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectJpaRepository extends JpaRepository<SubjectEntity, UUID> {

  Optional<SubjectEntity> findByDocumentTypeAndDocument(String documentType, String document);
}
