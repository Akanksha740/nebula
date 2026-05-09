package com.nebula.ingestion.repository;

import com.nebula.common.entity.MarketSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MarketSnapshotRepository extends JpaRepository<MarketSnapshot, Long> {

    List<MarketSnapshot> findByMarketIdOrderByTimeDesc(UUID marketId);

    Optional<MarketSnapshot> findFirstByMarketIdOrderByTimeDesc(UUID marketId);

    @Modifying
    @Query("DELETE FROM MarketSnapshot s WHERE s.market.id IN :marketIds")
    int deleteByMarketIdIn(@Param("marketIds") Collection<UUID> marketIds);
}
