package com.barrier.riskengine.screening.repository.interfaces;

import java.util.List;
import java.util.UUID;

import com.barrier.riskengine.screening.repository.WatchlistEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchlistEntryJpaRepository extends JpaRepository<WatchlistEntryEntity, UUID> {

  List<WatchlistEntryEntity> findByDocument(String document);

  void deleteBySource(String source);
}
