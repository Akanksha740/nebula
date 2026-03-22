package com.nebula.api.repository;

import com.nebula.common.entity.Coin;
import com.nebula.common.entity.Market;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MarketRepository extends JpaRepository<Market, UUID> {

    Optional<Market> findBySlug(String slug);

    Optional<Market> findByMarketId(String marketId);

    List<Market> findByActiveTrue();

    // Queries with coin filter
    Page<Market> findByCoinOrderByStartTimeDesc(Coin coin, Pageable pageable);
    long countByCoin(Coin coin);

    Page<Market> findByCoinAndMarketTypeOrderByStartTimeDesc(Coin coin, String marketType, Pageable pageable);
    long countByCoinAndMarketType(Coin coin, String marketType);

    Page<Market> findByCoinAndResolvedTrueOrderByStartTimeDesc(Coin coin, Pageable pageable);
    long countByCoinAndResolvedTrue(Coin coin);

    Page<Market> findByCoinAndResolvedFalseOrderByStartTimeDesc(Coin coin, Pageable pageable);
    long countByCoinAndResolvedFalse(Coin coin);

    Page<Market> findByCoinAndMarketTypeAndResolvedTrueOrderByStartTimeDesc(Coin coin, String marketType, Pageable pageable);
    long countByCoinAndMarketTypeAndResolvedTrue(Coin coin, String marketType);

    Page<Market> findByCoinAndMarketTypeAndResolvedFalseOrderByStartTimeDesc(Coin coin, String marketType, Pageable pageable);
    long countByCoinAndMarketTypeAndResolvedFalse(Coin coin, String marketType);

    long countByCoinAndMarketTypeAndStartTimeAfter(Coin coin, String marketType, Instant startTime);
}
