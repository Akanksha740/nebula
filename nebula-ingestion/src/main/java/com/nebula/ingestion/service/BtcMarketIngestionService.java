package com.nebula.ingestion.service;

import com.nebula.common.entity.Coin;
import com.nebula.common.entity.Market;
import com.nebula.common.entity.MarketSnapshot;
import com.nebula.ingestion.client.BinanceClient;
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

import com.nebula.ingestion.util.SlugGenerator;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BtcMarketIngestionService {

    private final PolymarketClient polymarketClient;
    private final ClobClient clobClient;
    private final BinanceClient binanceClient;
    private final MarketRepository marketRepository;
    private final MarketSnapshotRepository snapshotRepository;
    private final MarketPersistenceService persistenceService;

    /**
     * Generate slugs for all market types based on current UTC time,
     * ensure each market exists in DB, and take a snapshot.
     */
    public void snapshotActiveMarkets() {
        snapshotSlugs(SlugGenerator.generateCurrentSlugs());
    }

    /**
     * Snapshot short-duration markets (5m, 15m, 1hr).
     */
    public void snapshotShortMarkets() {
        snapshotSlugs(SlugGenerator.generateShortMarketSlugs(Instant.now()));
    }

    /**
     * Snapshot long-duration markets (4h, 24h).
     */
    public void snapshotLongMarkets() {
        snapshotSlugs(SlugGenerator.generateLongMarketSlugs(Instant.now()));
    }

    private void snapshotSlugs(List<String> slugs) {
        Map<Coin, BigDecimal> coinPrices = fetchAllCoinPrices();

        int snapshotCount = 0;
        for (String slug : slugs) {
            try {
                Market market = findOrCreateMarket(slug);
                if (market != null && market.getActive()) {
                    BigDecimal coinPrice = coinPrices.get(market.getCoin());
                    fetchAndSaveSnapshot(market, coinPrice);
                    snapshotCount++;
                }
            } catch (Exception e) {
                log.error("Failed to snapshot market {}: {}", slug, e.getMessage());
            }
        }

        if (snapshotCount > 0) {
            log.debug("Took {} snapshots for runtime slugs", snapshotCount);
        }
    }

    /**
     * Look up a market by slug; if it doesn't exist, fetch from Polymarket and persist it.
     * Returns null if the market cannot be found on Polymarket either.
     */
    private Market findOrCreateMarket(String slug) {
        Market existing = persistenceService.findBySlug(slug).orElse(null);
        if (existing != null) {
            return existing;
        }

        try {
            PolymarketMarket pm = polymarketClient.fetchMarketBySlug(slug)
                    .block(Duration.ofSeconds(30));

            if (pm == null) {
                log.debug("Market not found on Polymarket for slug: {}", slug);
                return null;
            }

            Coin coin = Coin.fromSlug(slug);
            if (coin == null) {
                log.warn("Could not determine coin from slug: {}", slug);
                return null;
            }

            Market market = Market.builder()
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
                    .coinPriceStart(pm.getCoinPriceStart())
                    .clobTokenUp(cleanTokenId(pm.getClobTokenUp()))
                    .clobTokenDown(cleanTokenId(pm.getClobTokenDown()))
                    .build();

            Market created = persistenceService.createMarketIfAbsent(market);
            if (created != null) {
                log.info("Auto-created market from runtime slug: {} (type: {})", slug, pm.getMarketTypeResolved());
                return created;
            }
            // Another thread created it — fetch the existing one
            return persistenceService.findBySlug(slug).orElse(null);
        } catch (Exception e) {
            log.debug("Could not fetch/create market for slug {}: {}", slug, e.getMessage());
            return null;
        }
    }

    /**
     * Fetch market data and save snapshot
     */
    private void fetchAndSaveSnapshot(Market market, BigDecimal coinPrice) {
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
                Instant closedTime = pm.getClosedTimeInstant();
                market.setResolvedAt(closedTime != null ? closedTime : Instant.now());
                BigDecimal pmEnd = pm.getCoinPriceEnd();
                market.setCoinPriceEnd(pmEnd != null ? pmEnd : coinPrice);
                market.setFinalVolume(pm.getVolume());
                market.setFinalLiquidity(pm.getLiquidity());
                log.info("Market {} is RESOLVED", slug);
            }

            persistenceService.saveMarket(market);

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
                    .coinPrice(coinPrice)
                    .priceUp(pm.getPriceUp())
                    .priceDown(pm.getPriceDown())
                    .volume(pm.getVolume())
                    .liquidity(pm.getLiquidity())
                    .orderbookUp(orderbookUp)
                    .orderbookDown(orderbookDown)
                    .build();
            
            persistenceService.saveSnapshot(snapshot);
            log.debug("Snapshot {} - Up: {}, Down: {}", slug, pm.getPriceUp(), pm.getPriceDown());
            
        } catch (Exception e) {
            log.warn("Error fetching market {}: {}", slug, e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                market.setActive(false);
                persistenceService.saveMarket(market);
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
    }

    /**
     * Fetch prices for all active coins in a single pass (one Binance call per coin).
     */
    private Map<Coin, BigDecimal> fetchAllCoinPrices() {
        Map<Coin, BigDecimal> prices = new EnumMap<>(Coin.class);
        BigDecimal btcPrice = binanceClient.fetchBtcPrice().block(Duration.ofSeconds(5));
        BigDecimal ethPrice = binanceClient.fetchEthPrice().block(Duration.ofSeconds(5));
        prices.put(Coin.BTC, btcPrice);
        prices.put(Coin.ETH, ethPrice);
        log.info("Binance prices - BTC: {}, ETH: {}", btcPrice, ethPrice);
        return prices;
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
        return marketRepository.findCurrentlyActiveMarkets(Instant.now()).size();
    }

    public List<String> getActiveMarketSlugs() {
        return marketRepository.findCurrentlyActiveMarkets(Instant.now()).stream()
                .map(Market::getSlug)
                .toList();
    }

    @Transactional
    public int deactivateExpiredMarkets() {
        List<Market> expiredMarkets = marketRepository.findExpiredActiveMarkets(Instant.now().minus(Duration.ofMinutes(15)));

        for (Market market : expiredMarkets) {
            try {
                PolymarketMarket pm = polymarketClient.fetchMarketBySlug(market.getSlug())
                        .block(Duration.ofSeconds(30));

                if (pm != null) {
                    // Polymarket is source of truth; fall back to Binance
                    BigDecimal pmStart = pm.getCoinPriceStart();
                    BigDecimal pmEnd = pm.getCoinPriceEnd();
                    BigDecimal coinPriceEnd = pmEnd != null ? pmEnd : market.getCoinPriceEnd();

                    market.setResolved(true);
                    Instant closedTime = pm.getClosedTimeInstant();
                    market.setResolvedAt(closedTime != null ? closedTime : market.getEndTime());
                    market.setWinner(pm.getWinnerFromPrices());
                    if (market.getCoinPriceStart() == null && pmStart != null) {
                        market.setCoinPriceStart(pmStart);
                    }
                    market.setCoinPriceEnd(coinPriceEnd);
                    market.setFinalVolume(pm.getVolume());
                    market.setFinalLiquidity(pm.getLiquidity());
                    log.info("Updated expired market {}", market.getSlug());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch final details for expired market {}: {}", market.getSlug(), e.getMessage());
            }

            market.setActive(false);
            marketRepository.save(market);
        }

        if (!expiredMarkets.isEmpty()) {
            log.info("Deactivated {} expired markets", expiredMarkets.size());
        }
        return expiredMarkets.size();
    }
}
