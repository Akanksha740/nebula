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
import com.nebula.ingestion.util.SlugGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class BtcMarketIngestionService {

    private final PolymarketClient polymarketClient;
    private final ClobClient clobClient;
    private final BinanceClient binanceClient;
    private final ChainlinkClient chainlinkClient;
    private final MarketRepository marketRepository;
    private final MarketPersistenceService persistenceService;

    /**
     * Snapshot short-duration markets (5m, 15m, 1hr).
     * BTC price from Chainlink (fallback to Binance), ETH and SOL from Binance.
     */
    public void snapshotShortMarkets() {
        Map<Coin, BigDecimal> prices = fetchPrices();
        snapshotSlugs(SlugGenerator.generateShortMarketSlugs(Instant.now()), prices);
    }

    /**
     * Snapshot long-duration markets (4h, 24h) using Binance prices.
     */
    public void snapshotLongMarkets() {
        Map<Coin, BigDecimal> prices = fetchBinancePrices();
        snapshotSlugs(SlugGenerator.generateLongMarketSlugs(Instant.now()), prices);
    }

    private void snapshotSlugs(List<String> slugs, Map<Coin, BigDecimal> prices) {
        int snapshotCount = 0;
        for (String slug : slugs) {
            try {
                Market market = findOrCreateMarket(slug, prices);
                if (market != null && market.getActive()) {
                    BigDecimal coinPrice = prices.get(market.getCoin());
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
    private Market findOrCreateMarket(String slug, Map<Coin, BigDecimal> coinPrices) {
        Market existing = persistenceService.findBySlug(slug).orElse(null);
        if (existing != null) {
            return existing;
        }

        try {
            PolymarketMarket pm = polymarketClient.fetchMarketBySlug(slug).block(Duration.ofSeconds(30));
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
            PolymarketMarket pm = polymarketClient.fetchMarketBySlug(slug).block(Duration.ofSeconds(30));

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

    private Map<Coin, BigDecimal> fetchBinancePrices() {
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
     * BTC from Chainlink (fallback to Binance), ETH and SOL from Binance.
     */
    private Map<Coin, BigDecimal> fetchPrices() {
        Map<Coin, BigDecimal> prices = new EnumMap<>(Coin.class);

        BigDecimal btcPrice = chainlinkClient.getBtcPrice();
        if (btcPrice == null) {
            log.warn("Chainlink BTC price unavailable, falling back to Binance");
            btcPrice = binanceClient.fetchBtcPrice().block(Duration.ofSeconds(5));
        }
        BigDecimal ethPrice = binanceClient.fetchEthPrice().block(Duration.ofSeconds(5));
        BigDecimal solPrice = binanceClient.fetchSolPrice().block(Duration.ofSeconds(5));

        prices.put(Coin.BTC, btcPrice);
        prices.put(Coin.ETH, ethPrice);
        prices.put(Coin.SOL, solPrice);
        log.debug("Prices - BTC: {} (Chainlink), ETH: {} (Binance), SOL: {} (Binance)", btcPrice, ethPrice, solPrice);
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
        return marketRepository.findCurrentlyActiveMarkets(Instant.now()).stream().map(Market::getSlug).toList();
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
