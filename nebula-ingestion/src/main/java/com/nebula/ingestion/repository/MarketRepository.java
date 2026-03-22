package com.nebula.ingestion.repository;

import com.nebula.common.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MarketRepository extends JpaRepository<Market, UUID> {

    Optional<Market> findBySlug(String slug);

    List<Market> findByActiveTrue();

    @Query("SELECT m FROM Market m WHERE m.active = true AND m.startTime <= :now AND m.endTime >= :now")
    List<Market> findCurrentlyActiveMarkets(@Param("now") Instant now);

    @Query("SELECT m FROM Market m WHERE m.active = true AND m.endTime < :now")
    List<Market> findExpiredActiveMarkets(@Param("now") Instant now);

    @Modifying
    @Query(value = "INSERT INTO markets (id, coin, slug, market_id, event_id, market_type, condition_id, " +
            "clob_token_up, clob_token_down, start_time, end_time, coin_price_start, " +
            "active, resolved, created_at, updated_at) " +
            "VALUES (:id, :coin, :slug, :marketId, :eventId, :marketType, :conditionId, " +
            ":clobTokenUp, :clobTokenDown, :startTime, :endTime, :coinPriceStart, " +
            "true, false, NOW(), NOW()) " +
            "ON CONFLICT (slug) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("coin") String coin, @Param("slug") String slug,
                       @Param("marketId") String marketId, @Param("eventId") String eventId,
                       @Param("marketType") String marketType, @Param("conditionId") String conditionId,
                       @Param("clobTokenUp") String clobTokenUp, @Param("clobTokenDown") String clobTokenDown,
                       @Param("startTime") Instant startTime, @Param("endTime") Instant endTime,
                       @Param("coinPriceStart") BigDecimal coinPriceStart);

}
