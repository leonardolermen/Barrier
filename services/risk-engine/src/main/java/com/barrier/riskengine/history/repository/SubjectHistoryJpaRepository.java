package com.barrier.riskengine.history.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SubjectHistoryJpaRepository extends JpaRepository<SubjectHistoryEntity, UUID> {

  List<SubjectHistoryEntity> findBySubjectId(UUID subjectId);
}
