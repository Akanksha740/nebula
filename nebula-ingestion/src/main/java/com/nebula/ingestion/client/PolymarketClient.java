package com.nebula.ingestion.client;

import com.nebula.ingestion.client.dto.PolymarketMarket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PolymarketClient {

    private final WebClient webClient;

    @Value("${polymarket.api.base-url:https://gamma-api.polymarket.com}")
    private String baseUrl;

    @Value("${polymarket.api.retry-attempts:3}")
    private int retryAttempts;

    @Value("${polymarket.api.retry-delay-ms:1000}")
    private long retryDelayMs;

    /**
     * Fetch BTC Up/Down market by epoch time slot
     * URL pattern: https://gamma-api.polymarket.com/markets/slug/btc-updown-5m-{epochSeconds}
     */
    public Mono<PolymarketMarket> fetchBtcUpDownMarket(long epochSeconds) {
        String slug = "btc-updown-5m-" + epochSeconds;
        String url = baseUrl + "/markets/slug/" + slug;
        
        log.info("Fetching BTC Up/Down market: {}", url);
        
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(PolymarketMarket.class)
                .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryDelayMs))
                        .doBeforeRetry(signal -> 
                            log.warn("Retrying BTC market fetch, attempt: {}", signal.totalRetries() + 1)))
                .doOnSuccess(market -> 
                    log.info("Successfully fetched BTC market: {} - {}", slug, 
                        market != null ? market.getQuestion() : "null"))
                .doOnError(error -> 
                    log.error("Failed to fetch BTC market {}: {}", slug, error.getMessage()));
    }

    /**
     * Calculate the current 5-minute epoch slot
     * Rounds down to the nearest 5-minute interval
     */
    public static long getCurrentEpochSlot() {
        long now = Instant.now().getEpochSecond();
        return (now / 300) * 300; // Round down to nearest 5 minutes (300 seconds)
    }

    /**
     * Calculate the previous 5-minute epoch slot
     */
    public static long getPreviousEpochSlot() {
        return getCurrentEpochSlot() - 300;
    }
    /**

     * Fetch active markets from Polymarket
     */
    public Mono<List<PolymarketMarket>> fetchActiveMarkets() {
        return webClient.get()
                .uri(baseUrl + "/markets?active=true&limit=100")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<PolymarketMarket>>() {})
                .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryDelayMs))
                        .doBeforeRetry(signal -> 
                            log.warn("Retrying Polymarket API call, attempt: {}", 
                                signal.totalRetries() + 1)))
                .doOnSuccess(markets -> 
                    log.info("Successfully fetched {} markets from Polymarket", 
                        markets != null ? markets.size() : 0))
                .doOnError(error -> 
                    log.error("Failed to fetch markets from Polymarket", error));
    }

    public Mono<PolymarketMarket> fetchMarketById(String marketId) {
        return webClient.get()
                .uri(baseUrl + "/markets/{id}", marketId)
                .retrieve()
                .bodyToMono(PolymarketMarket.class)
                .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryDelayMs)))
                .doOnError(error -> 
                    log.error("Failed to fetch market {} from Polymarket", marketId, error));
    }

    public Mono<PolymarketMarket> fetchMarketBySlug(String slug) {
        return webClient.get()
                .uri(baseUrl + "/markets/slug/{slug}", slug)
                .retrieve()
                .bodyToMono(PolymarketMarket.class)
                .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryDelayMs)))
                .doOnError(error -> 
                    log.error("Failed to fetch market by slug {} from Polymarket", slug, error));
    }
}
