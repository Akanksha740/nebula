package com.nebula.ingestion.service;

import com.nebula.common.entity.Coin;
import com.nebula.common.entity.Market;
import com.nebula.common.entity.MarketSnapshot;
import com.nebula.ingestion.client.ClobClient;
import com.nebula.ingestion.client.PolymarketClient;
import com.nebula.ingestion.client.dto.OrderbookResponse;
import com.nebula.ingestion.client.dto.PolymarketMarket;
import com.nebula.ingestion.repository.MarketRepository;
import com.nebula.ingestion.repository.MarketSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BtcMarketIngestionService {

    private final PolymarketClient polymarketClient;
    private final ClobClient clobClient;
    private final MarketRepository marketRepository;
    private final MarketSnapshotRepository snapshotRepository;

    /**
     * Add slugs to track - fetches market data from Polymarket API and saves to DB
     */
    @Transactional
    public int addSlugsToTrack(List<String> slugs) {
        int addedCount = 0;
        
        for (String slug : slugs) {
            if (slug == null || slug.isBlank()) continue;
            
            try {
                // Fetch market data from Polymarket API
                PolymarketMarket pm = polymarketClient.fetchMarketBySlug(slug)
                    .block(Duration.ofSeconds(30));
                
                if (pm == null) {
                    log.warn("Market not found on Polymarket: {}", slug);
                    continue;
                }
                
                // Extract coin from slug
                Coin coin = Coin.fromSlug(slug);
                if (coin == null) {
                    log.warn("Could not determine coin from slug: {}", slug);
                    continue;
                }
                
                Market market = marketRepository.findBySlug(slug).orElse(null);
                
                if (market == null) {
                    // Create new market with data from API
                    market = Market.builder()
                            .slug(slug)
                            .coin(coin)
                            .active(true)
                            .resolved(false)
                            .marketId(pm.getId())
                            .eventId(pm.getEventId())
                            .conditionId(pm.getConditionId())
                            .marketType(pm.getMarketTypeResolved())
                            .startTime(pm.getEventStartTime())
                            .endTime(pm.getEndDate())
                            .btcPriceStart(pm.getBtcPriceStart())
                            .clobTokenUp(cleanTokenId(pm.getClobTokenUp()))
                            .clobTokenDown(cleanTokenId(pm.getClobTokenDown()))
                            .build();
                    marketRepository.save(market);
                    log.info("Added {} slug to tracking: {} (startTime: {}, endTime: {})", 
                            coin, slug, pm.getEventStartTime(), pm.getEndDate());
                    addedCount++;
                } else if (!market.getActive()) {
                    // Reactivate existing market (do NOT update start_time and end_time)
                    market.setActive(true);
                    marketRepository.save(market);
                    log.info("Reactivated slug: {}", slug);
                } else {
                    log.info("Slug already being tracked: {}", slug);
                }
            } catch (Exception e) {
                log.error("Failed to fetch market data for slug {}: {}", slug, e.getMessage());
            }
        }
        
        return addedCount;
    }

    /**
     * Take snapshots of markets within their active time window
     */
    @Transactional
    public void snapshotActiveMarkets() {
        Instant now = Instant.now();
        List<Market> markets = marketRepository.findMarketsToSnapshot(now);
        
        if (markets.isEmpty()) {
            return;
        }

        int snapshotCount = 0;
        for (Market market : markets) {
            try {
                fetchAndSaveSnapshot(market);
                snapshotCount++;
            } catch (Exception e) {
                log.error("Failed to snapshot market {}: {}", market.getSlug(), e.getMessage());
            }
        }
        
        if (snapshotCount > 0) {
            log.info("Took {} snapshots", snapshotCount);
        }
    }

    /**
     * Fetch market data and save snapshot
     */
    private void fetchAndSaveSnapshot(Market market) {
        String slug = market.getSlug();
        
        try {
            PolymarketMarket pm = polymarketClient.fetchMarketBySlug(slug)
                .block(Duration.ofSeconds(30));
            
            if (pm == null) {
                log.warn("Market {} not found", slug);
                return;
            }
            
            // Update market info (but NOT start_time and end_time)
            updateMarketFromPolymarket(market, pm);
            
            // Check if resolved
            boolean isResolved = isMarketResolved(pm);
            if (isResolved && !market.getResolved()) {
                market.setActive(false);
                market.setResolved(true);
                market.setResolvedAt(Instant.now());
                market.setWinner(pm.getWinner());
                market.setBtcPriceEnd(pm.getBtcPriceStart());
                market.setFinalVolume(pm.getVolume());
                market.setFinalLiquidity(pm.getLiquidity());
                log.info("Market {} is RESOLVED", slug);
            }
            
            marketRepository.save(market);
            
            // Fetch both orderbooks in a single API call
            List<OrderbookResponse> orderbooks = clobClient.fetchOrderbooks(
                    market.getClobTokenUp(), market.getClobTokenDown())
                    .block(Duration.ofSeconds(10));
            
            Map<String, Object> orderbookUp = ClobClient.getOrderbookForToken(orderbooks, market.getClobTokenUp());
            Map<String, Object> orderbookDown = ClobClient.getOrderbookForToken(orderbooks, market.getClobTokenDown());
            
            // Create snapshot
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .market(market)
                    .time(Instant.now())
                    .btcPrice(pm.getBtcPriceStart())
                    .priceUp(pm.getPriceUp())
                    .priceDown(pm.getPriceDown())
                    .volume(pm.getVolume())
                    .liquidity(pm.getLiquidity())
                    .orderbookUp(orderbookUp)
                    .orderbookDown(orderbookDown)
                    .build();
            
            snapshotRepository.save(snapshot);
            log.debug("Snapshot {} - Up: {}, Down: {}", slug, pm.getPriceUp(), pm.getPriceDown());
            
        } catch (Exception e) {
            log.warn("Error fetching market {}: {}", slug, e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                market.setActive(false);
                marketRepository.save(market);
                log.info("Market {} not found (404) - marked inactive", slug);
            }
        }
    }

    /**
     * Update market from Polymarket data (does NOT update start_time and end_time)
     */
    private void updateMarketFromPolymarket(Market market, PolymarketMarket pm) {
        if (market.getMarketId() == null) {
            market.setMarketId(pm.getId());
        }
        if (market.getEventId() == null) {
            market.setEventId(pm.getEventId());
        }
        if (market.getConditionId() == null) {
            market.setConditionId(pm.getConditionId());
        }
        
        // Always update clob tokens with clean values
        String clobTokenUp = cleanTokenId(pm.getClobTokenUp());
        String clobTokenDown = cleanTokenId(pm.getClobTokenDown());
        
        if (clobTokenUp != null && !clobTokenUp.isEmpty()) {
            market.setClobTokenUp(clobTokenUp);
        }
        if (clobTokenDown != null && !clobTokenDown.isEmpty()) {
            market.setClobTokenDown(clobTokenDown);
        }
        
        if (market.getMarketType() == null) {
            market.setMarketType(pm.getMarketTypeResolved());
        }
        // NOTE: start_time and end_time are NOT updated here - only set during ingestion
        if (market.getBtcPriceStart() == null) {
            market.setBtcPriceStart(pm.getBtcPriceStart());
        }
    }

    private String cleanTokenId(String tokenId) {
        if (tokenId == null) return null;
        String cleaned = tokenId.replaceAll("[\\[\\]\"]", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private boolean isMarketResolved(PolymarketMarket market) {
        if (Boolean.TRUE.equals(market.getClosed())) return true;
        if (Boolean.FALSE.equals(market.getActive())) return true;
        
        String status = market.getStatus();
        if (status != null) {
            String lower = status.toLowerCase();
            if (lower.contains("resolved") || lower.contains("closed")) return true;
        }
        
        if (market.getEndDate() != null) {
            if (Instant.now().isAfter(market.getEndDate().plusSeconds(120))) return true;
        }
        
        return false;
    }

    public int getActiveMarketCount() {
        return marketRepository.findByActiveTrue().size();
    }

    public List<String> getActiveMarketSlugs() {
        return marketRepository.findByActiveTrue().stream()
                .map(Market::getSlug)
                .toList();
    }

    @Transactional
    public int deactivateExpiredMarkets() {
        int count = marketRepository.deactivateExpiredMarkets(Instant.now());
        if (count > 0) {
            log.info("Deactivated {} expired markets", count);
        }
        return count;
    }
}
