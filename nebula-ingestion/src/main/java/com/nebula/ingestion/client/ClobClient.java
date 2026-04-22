package com.nebula.ingestion.client;

import com.nebula.ingestion.client.dto.OrderbookResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClobClient {

    private final WebClient webClient;

    @Value("${polymarket.clob.base-url:https://clob.polymarket.com}")
    private String clobBaseUrl;

    /**
     * Fetch orderbooks for multiple tokens in a single POST call
     */
    public Mono<List<OrderbookResponse>> fetchOrderbooks(String tokenIdUp, String tokenIdDown) {
        List<Map<String, String>> requestBody = new ArrayList<>();
        
        if (tokenIdUp != null && !tokenIdUp.isBlank()) {
            requestBody.add(Map.of("token_id", tokenIdUp));
        }
        if (tokenIdDown != null && !tokenIdDown.isBlank()) {
            requestBody.add(Map.of("token_id", tokenIdDown));
        }
        
        if (requestBody.isEmpty()) {
            return Mono.just(List.of());
        }

        String url = clobBaseUrl + "/books";
        log.debug("Fetching orderbooks via POST: {}", url);

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<OrderbookResponse>>() {})
                .retryWhen(Retry.backoff(2, Duration.ofMillis(500)))
                .doOnError(error ->
                    log.warn("Failed to fetch orderbooks: {}", error.getMessage()))
                .onErrorResume(e -> Mono.just(List.of()));
    }

    /**
     * Fetch last trade price for a token via GET /last-trade-price?token_id=...
     */
    public Mono<BigDecimal> fetchLastTradePrice(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return Mono.empty();
        }
        return webClient.get()
                .uri(clobBaseUrl + "/last-trade-price?token_id={tokenId}", tokenId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {})
                .map(response -> new BigDecimal(response.get("price")))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(500)))
                .doOnError(error ->
                    log.warn("Failed to fetch last trade price for token {}: {}", tokenId, error.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Get orderbook map for a specific token from the batch response (limited to top 10 levels)
     */
    public static Map<String, Object> getOrderbookForToken(List<OrderbookResponse> orderbooks, String tokenId) {
        if (orderbooks == null || tokenId == null) {
            return Map.of("bids", List.of(), "asks", List.of());
        }
        return orderbooks.stream()
                .filter(ob -> tokenId.equals(ob.getAssetId()))
                .findFirst()
                .map(ob -> ob.toOrderbookMap(10))
                .orElse(Map.of("bids", List.of(), "asks", List.of()));
    }
}
