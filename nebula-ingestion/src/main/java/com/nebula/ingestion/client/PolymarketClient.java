package com.nebula.ingestion.client;

import com.nebula.ingestion.client.dto.PolymarketMarket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

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

    public Mono<PolymarketMarket> fetchMarketBySlug(String slug) {
        return webClient.get()
                .uri(baseUrl + "/markets/slug/{slug}", slug)
                .retrieve()
                .bodyToMono(PolymarketMarket.class)
                .onErrorResume(WebClientResponseException.NotFound.class, e -> {
                    log.debug("Market not found on Polymarket for slug: {}", slug);
                    return Mono.empty();
                })
                .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryDelayMs))
                        .filter(ex -> !(ex instanceof WebClientResponseException.NotFound)))
                .doOnError(error ->
                    log.error("Failed to fetch market by slug {} from Polymarket", slug, error));
    }
}
