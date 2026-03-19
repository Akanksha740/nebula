package com.nebula.ingestion.repository;

import com.nebula.common.entity.MarketSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MarketSnapshotRepository extends JpaRepository<MarketSnapshot, Long> {

    List<MarketSnapshot> findByMarketIdOrderByTimeDesc(UUID marketId);
}
