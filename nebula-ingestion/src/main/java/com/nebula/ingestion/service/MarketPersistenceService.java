package com.nebula.ingestion.service;

import com.nebula.common.entity.Market;
import com.nebula.common.entity.MarketSnapshot;
import com.nebula.ingestion.repository.MarketRepository;
import com.nebula.ingestion.repository.MarketSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketPersistenceService {

    private final MarketRepository marketRepository;
    private final MarketSnapshotRepository snapshotRepository;

    public Optional<Market> findBySlug(String slug) {
        return marketRepository.findBySlug(slug);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Market saveMarket(Market market) {
        return marketRepository.saveAndFlush(market);
    }

    /**
     * Inserts a new market using ON CONFLICT DO NOTHING to avoid duplicate key errors.
     * Returns the market (either newly created or existing) or null if not found.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Market createMarketIfAbsent(Market market) {
        UUID id = UUID.randomUUID();
        int inserted = marketRepository.insertIfAbsent(
                id,
                market.getCoin().name(),
                market.getSlug(),
                market.getMarketId(),
                market.getEventId(),
                market.getMarketType(),
                market.getConditionId(),
                market.getClobTokenUp(),
                market.getClobTokenDown(),
                market.getStartTime(),
                market.getEndTime(),
                market.getCoinPriceStart()
        );

        if (inserted > 0) {
            return marketRepository.findById(id).orElse(null);
        }
        // Another thread already created it
        return null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSnapshot(MarketSnapshot snapshot) {
        snapshotRepository.save(snapshot);
    }

    public Optional<MarketSnapshot> findLatestSnapshot(UUID marketId) {
        return snapshotRepository.findFirstByMarketIdOrderByTimeDesc(marketId);
    }
}
