package com.barrier.riskengine.assessment.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA — acesso técnico à tabela {@code assessments}. */
interface AssessmentJpaRepository extends JpaRepository<AssessmentEntity, UUID> {

}
