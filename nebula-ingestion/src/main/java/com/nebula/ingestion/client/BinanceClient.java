package com.nebula.ingestion.client;

import com.nebula.ingestion.client.dto.BinanceTickerResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class BinanceClient {

    private final WebClient webClient;

    @Value("${binance.api.base-url:https://api.binance.com}")
    private String baseUrl;

    /**
     * Fetch latest BTC/USDT price from Binance
     */
    public Mono<BigDecimal> fetchBtcPrice() {
        return fetchPrice("BTCUSDT");
    }

    /**
     * Fetch latest ETH/USDT price from Binance
     */
    public Mono<BigDecimal> fetchEthPrice() {
        return fetchPrice("ETHUSDT");
    }

    /**
     * Fetch latest price for a given symbol from Binance
     */
    public Mono<BigDecimal> fetchPrice(String symbol) {
        return webClient.get()
                .uri(baseUrl + "/api/v3/ticker/price?symbol=" + symbol)
                .retrieve()
                .bodyToMono(BinanceTickerResponse.class)
                .map(BinanceTickerResponse::getPrice)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(500)))
                .doOnError(error ->
                    log.warn("Failed to fetch {} price from Binance: {}", symbol, error.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }
}
