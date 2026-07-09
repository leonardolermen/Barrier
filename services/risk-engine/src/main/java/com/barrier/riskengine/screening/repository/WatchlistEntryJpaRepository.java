package com.barrier.riskengine.screening.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WatchlistEntryJpaRepository extends JpaRepository<WatchlistEntryEntity, UUID> {

  List<WatchlistEntryEntity> findByDocument(String document);

  void deleteBySource(String source);
}
