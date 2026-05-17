package com.nebula.ingestion.service;

import com.nebula.common.entity.Coin;
import com.nebula.common.entity.Market;
import com.nebula.common.entity.MarketSnapshot;
import com.nebula.ingestion.client.BinanceClient;
import com.nebula.ingestion.client.ChainlinkClient;
import com.nebula.ingestion.client.ClobClient;
import com.nebula.ingestion.client.PolymarketClient;
import com.nebula.ingestion.client.dto.OrderbookResponse;
import com.nebula.ingestion.client.dto.PolymarketMarket;
import com.nebula.ingestion.repository.MarketRepository;
import com.nebula.ingestion.repository.MarketSnapshotRepository;
import com.nebula.ingestion.util.SlugGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BtcMarketIngestionService {

    private final PolymarketClient polymarketClient;
    private final ClobClient clobClient;
    private final BinanceClient binanceClient;
    private final ChainlinkClient chainlinkClient;
    private final MarketRepository marketRepository;
    private final MarketSnapshotRepository snapshotRepository;
    private final MarketPersistenceService persistenceService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Generate slugs for all market types based on current UTC time,
     * ensure each market exists in DB, and take a snapshot.
     */
    public void snapshotActiveMarkets() {
        snapshotSlugs(SlugGenerator.generateCurrentSlugs());
    }

    /**
     * Snapshot short-duration markets (5m, 15m, 1hr) EXCEPT BTC 5m, which
     * is handled by {@link #snapshotBtc5mMarket()} at a higher cadence.
     */
    public void snapshotShortMarkets() {
        List<String> slugs = SlugGenerator.generateShortMarketSlugs(Instant.now()).stream()
                .filter(s -> !s.startsWith("btc-updown-5m-"))
                .toList();
        snapshotSlugs(slugs);
    }

    /**
     * Dedicated high-frequency snapshot path for BTC 5m markets.
     * Called every 100 ms by the scheduler — gives ~10 snapshots/sec/market.
     */
    public void snapshotBtc5mMarket() {
        snapshotSlugs(List.of(SlugGenerator.generate5mSlug(Instant.now())), fetchCoinPricesWithChainlinkBtc());
    }

    /**
     * Snapshot long-duration markets (4h, 24h).
     */
    public void snapshotLongMarkets() {
        snapshotSlugs(SlugGenerator.generateLongMarketSlugs(Instant.now()));
    }

    private void snapshotSlugs(List<String> slugs) {
        snapshotSlugs(slugs, fetchAllCoinPrices());
    }

    private void snapshotSlugs(List<String> slugs, Map<Coin, BigDecimal> coinPrices) {
        int snapshotCount = 0;
        for (String slug : slugs) {
            try {
                Market market = findOrCreateMarket(slug, coinPrices);
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
     * Public entry point for ad-hoc slug processing. Creates the market in
     * our DB if it doesn't already exist (by fetching metadata from
     * Polymarket gamma-api). Returns null if Polymarket has no record.
     *
     * Used by the per-slug backfill admin endpoint — does NOT take a live
     * snapshot, since that would record current orderbook state for an
     * old market. Caller is expected to backfill historical snapshots
     * via {@link SnapshotBackupService#backfillSingleMarket}.
     */
    public Market createMarketFromPolymarket(String slug) {
        return findOrCreateMarket(slug, fetchAllCoinPrices());
    }

    /**
     * Look up a market by slug; if it doesn't exist, fetch from Polymarket and persist it.
     * Returns null if the market cannot be found on Polymarket either.
     */
    private Market findOrCreateMarket(String slug, Map<Coin, BigDecimal> coinPrices) {
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
                    .coinPriceStart(coinPrices.get(coin))
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
            boolean isResolved = isMarketResolved(pm);

            List<OrderbookResponse> orderbooks = clobClient.fetchOrderbooks(
                    market.getClobTokenUp(), market.getClobTokenDown())
                    .block(Duration.ofSeconds(10));

            Map<String, Object> orderbookUp = reverseOrderbook(ClobClient.getOrderbookForToken(orderbooks, market.getClobTokenUp()));
            Map<String, Object> orderbookDown = reverseOrderbook(ClobClient.getOrderbookForToken(orderbooks, market.getClobTokenDown()));

            // Fetch last trade prices from CLOB, fallback to Polymarket outcomePrices
            BigDecimal priceUp = clobClient.fetchLastTradePrice(market.getClobTokenUp())
                    .block(Duration.ofSeconds(5));
            if (priceUp == null) priceUp = pm.getPriceUp();
            BigDecimal priceDown = clobClient.fetchLastTradePrice(market.getClobTokenDown())
                    .block(Duration.ofSeconds(5));
            if (priceDown == null) priceDown = pm.getPriceDown();

            if (isResolved && !market.getResolved()) {
                market.setActive(false);
                market.setResolved(true);
                Instant closedTime = pm.getClosedTimeInstant();
                market.setResolvedAt(closedTime != null ? closedTime : Instant.now());
                market.setFinalVolume(pm.getVolume());
                market.setFinalLiquidity(pm.getLiquidity());
                MarketSnapshot lastSnapshot = persistenceService.findLatestSnapshot(market.getId()).orElse(null);
                market.setCoinPriceEnd(lastSnapshot != null ? lastSnapshot.getCoinPrice() : coinPrice);
                log.info("Market {} is RESOLVED", slug);
                persistenceService.saveMarket(market);
            }

            // Create snapshot
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .market(market)
                    .time(Instant.now())
                    .coinPrice(coinPrice)
                    .priceUp(priceUp)
                    .priceDown(priceDown)
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> reverseOrderbook(Map<String, Object> orderbook) {
        List<Map<String, Object>> bids = new ArrayList<>((List<Map<String, Object>>) orderbook.get("bids"));
        List<Map<String, Object>> asks = new ArrayList<>((List<Map<String, Object>>) orderbook.get("asks"));
        Collections.reverse(bids);
        Collections.reverse(asks);
        return Map.of("bids", bids, "asks", asks);
    }

    /**
     * Fetch prices for all active coins in a single pass (one Binance call per coin).
     */
    private Map<Coin, BigDecimal> fetchAllCoinPrices() {
        Map<Coin, BigDecimal> prices = new EnumMap<>(Coin.class);
        BigDecimal btcPrice = binanceClient.fetchBtcPrice().block(Duration.ofSeconds(5));
        BigDecimal ethPrice = binanceClient.fetchEthPrice().block(Duration.ofSeconds(5));
        BigDecimal solPrice = binanceClient.fetchSolPrice().block(Duration.ofSeconds(5));
        prices.put(Coin.BTC, btcPrice);
        prices.put(Coin.ETH, ethPrice);
        prices.put(Coin.SOL, solPrice);
        log.info("Binance prices - BTC: {}, ETH: {}, SOL: {}", btcPrice, ethPrice, solPrice);
        return prices;
    }

    /**
     * BTC 5m market snapshots use Chainlink BTC/USD via Polymarket RTDS.
     * Binance remains the fallback while the WebSocket cache is warming up.
     */
    private Map<Coin, BigDecimal> fetchCoinPricesWithChainlinkBtc() {
        Map<Coin, BigDecimal> prices = fetchAllCoinPrices();

        BigDecimal chainlinkBtcPrice = chainlinkClient.getBtcPrice();
        if (chainlinkBtcPrice == null) {
            log.warn("Chainlink BTC price unavailable for BTC 5m snapshot, falling back to Binance");
            return prices;
        }

        prices.put(Coin.BTC, chainlinkBtcPrice);
        log.debug("BTC 5m Chainlink price - BTC: {}", chainlinkBtcPrice);
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

    /**
     * Calculate final liquidity from orderbook depth:
     * Sum(orderbook_up.asks sizes) + Sum(orderbook_down.bids sizes)
     */
    @SuppressWarnings("unchecked")
    private BigDecimal calculateFinalLiquidity(Map<String, Object> orderbookUp, Map<String, Object> orderbookDown) {
        BigDecimal total = BigDecimal.ZERO;
        total = total.add(sumOrderbookSizes((List<Map<String, Object>>) orderbookUp.getOrDefault("asks", List.of())));
        total = total.add(sumOrderbookSizes((List<Map<String, Object>>) orderbookDown.getOrDefault("bids", List.of())));
        return total;
    }

    private BigDecimal sumOrderbookSizes(List<Map<String, Object>> levels) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> level : levels) {
            Object size = level.get("size");
            if (size instanceof Number) {
                sum = sum.add(BigDecimal.valueOf(((Number) size).doubleValue()));
            }
        }
        return sum;
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
        List<Market> expiredMarkets = marketRepository.findExpiredActiveMarkets(Instant.now());

        for (Market market : expiredMarkets) {
            MarketSnapshot lastSnapshot = persistenceService.findLatestSnapshot(market.getId()).orElse(null);
            if (lastSnapshot != null) {
                market.setCoinPriceEnd(lastSnapshot.getCoinPrice());
                market.setFinalVolume(lastSnapshot.getVolume());
                market.setFinalLiquidity(lastSnapshot.getLiquidity());
            }
            String winner = determineWinner(market);
            market.setWinner(winner);
            market.setResolvedAt(Instant.now());
            market.setIsResolved(true);
            market.setActive(false);
            marketRepository.save(market);
            log.info("Resolved expired market {} — winner={}", market.getSlug(), winner);
        }

        if (!expiredMarkets.isEmpty()) {
            log.info("Deactivated {} expired markets", expiredMarkets.size());
        }
        return expiredMarkets.size();
    }

    /**
     * Delete markets created more than {@code olderThanDays} days ago,
     * along with all their snapshots. Snapshots are removed first to satisfy
     * the FK from market_snapshots.market_id (the schema also has ON DELETE
     * CASCADE, but we delete explicitly so JPA's persistence context stays
     * consistent and we can log the snapshot count).
     */
    @Transactional
    public int deleteMarketsOlderThan(int olderThanDays) {
        Instant cutoff = Instant.now().minus(olderThanDays, ChronoUnit.DAYS);
        List<UUID> oldMarketIds = marketRepository.findIdsCreatedBefore(cutoff);

        if (oldMarketIds.isEmpty()) {
            log.info("No markets older than {} days (cutoff={}) found for cleanup", olderThanDays, cutoff);
            return 0;
        }

        int deletedSnapshots = snapshotRepository.deleteByMarketIdIn(oldMarketIds);
        int deletedMarkets = marketRepository.deleteByIdIn(oldMarketIds);
        log.info("Cleanup: deleted {} markets and {} snapshots older than {} days (cutoff={})",
                deletedMarkets, deletedSnapshots, olderThanDays, cutoff);
        return deletedMarkets;
    }

    /**
     * Run VACUUM on the market tables to release dead-tuple pages back to
     * Postgres so future inserts reuse them (prevents bloat re-accumulating
     * after the daily DELETE).
     *
     * Must NOT be wrapped in a transaction — Postgres rejects VACUUM inside
     * a transaction block. This method is intentionally non-@Transactional;
     * it must also not be invoked from inside a @Transactional caller.
     *
     * @param full  if true, runs VACUUM FULL (rewrites the table, returns
     *              disk to the OS, but takes an AccessExclusiveLock for the
     *              duration — only safe in maintenance windows). Default
     *              false runs an online VACUUM ANALYZE.
     */
    public void vacuumMarketTables(boolean full) {
        String sql = full
                ? "VACUUM (FULL, ANALYZE) markets, market_snapshots"
                : "VACUUM (ANALYZE) markets, market_snapshots";
        long startMs = System.currentTimeMillis();
        log.info("Running {} on markets, market_snapshots ...", full ? "VACUUM FULL ANALYZE" : "VACUUM ANALYZE");
        jdbcTemplate.execute(sql);
        log.info("VACUUM completed in {} ms", System.currentTimeMillis() - startMs);
    }

    private String determineWinner(Market market) {
        BigDecimal coinPriceStart = market.getCoinPriceStart();
        BigDecimal coinPriceEnd = market.getCoinPriceEnd();

        if (coinPriceStart != null && coinPriceEnd != null) {
            int cmp = coinPriceEnd.compareTo(coinPriceStart);
            if (cmp > 0) return "Up";
            if (cmp < 0) return "Down";
        }

        MarketSnapshot latestSnapshot = persistenceService.findLatestSnapshot(market.getId()).orElse(null);
        if (latestSnapshot != null && latestSnapshot.getPriceUp() != null && latestSnapshot.getPriceDown() != null) {
            int priceCmp = latestSnapshot.getPriceUp().compareTo(latestSnapshot.getPriceDown());
            if (priceCmp > 0) return "Up";
            if (priceCmp < 0) return "Down";
        }

        // Default fallback
        return "Up";
    }
}
