package com.nebula.ingestion.repository;

import com.nebula.common.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MarketRepository extends JpaRepository<Market, UUID> {

    Optional<Market> findBySlug(String slug);

    List<Market> findByActiveTrue();

    @Query("SELECT m FROM Market m WHERE m.active = true AND m.startTime <= :now AND m.endTime >= :now")
    List<Market> findMarketsToSnapshot(@Param("now") Instant now);

    @Modifying
    @Query("UPDATE Market m SET m.active = false WHERE m.active = true AND m.endTime < :now")
    int deactivateExpiredMarkets(@Param("now") Instant now);

    boolean existsBySlug(String slug);
}
