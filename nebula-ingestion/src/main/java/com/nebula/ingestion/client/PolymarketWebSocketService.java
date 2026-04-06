package com.nebula.ingestion.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maintains a persistent WebSocket connection to Polymarket's RTDS
 * for the Chainlink BTC/USD price feed.
 *
 * Price is cached in an AtomicReference for zero-latency reads.
 */
@Service
@Slf4j
public class PolymarketWebSocketService {

    @Value("${polymarket.rtds.ws-url:wss://ws-live-data.polymarket.com}")
    private String wsUrl;

    private static final Duration PING_INTERVAL = Duration.ofSeconds(5);
    private static final int MAX_RECONNECT_DELAY_SECONDS = 30;
    private static final String CHAINLINK_BTC_SYMBOL = "btc/usd";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<BigDecimal> chainlinkBtcPrice = new AtomicReference<>();
    private final AtomicReference<Instant> lastUpdated = new AtomicReference<>(Instant.EPOCH);

    private volatile Sinks.Many<String> outbound;
    private volatile Disposable connectionDisposable;
    private volatile Disposable pingDisposable;
    private volatile boolean running = false;
    private volatile int reconnectAttempts = 0;

    @PostConstruct
    public void start() {
        running = true;
        connect();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (pingDisposable != null && !pingDisposable.isDisposed()) {
            pingDisposable.dispose();
        }
        if (connectionDisposable != null && !connectionDisposable.isDisposed()) {
            connectionDisposable.dispose();
        }
    }

    /**
     * Returns the latest Chainlink BTC/USD price, or null if not yet received.
     */
    public BigDecimal getChainlinkBtcPrice() {
        return chainlinkBtcPrice.get();
    }

    /**
     * Returns true if the WebSocket is active and the price was updated within 60 seconds.
     */
    public boolean isConnected() {
        return Duration.between(lastUpdated.get(), Instant.now()).getSeconds() < 60;
    }

    // ── WebSocket lifecycle ──

    private void connect() {
        if (!running) return;

        outbound = Sinks.many().multicast().onBackpressureBuffer(256);

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(10));

        ReactorNettyWebSocketClient wsClient = new ReactorNettyWebSocketClient(httpClient);

        log.info("Polymarket RTDS connecting to {}", wsUrl);

        connectionDisposable = wsClient.execute(URI.create(wsUrl), this::handleSession)
                .doOnTerminate(() -> {
                    if (running) {
                        log.warn("Polymarket RTDS connection terminated, scheduling reconnect");
                        stopPing();
                        scheduleReconnect();
                    }
                })
                .doOnError(e -> {
                    if (running) {
                        log.warn("Polymarket RTDS error: {}", e.getMessage());
                    }
                })
                .subscribe();
    }

    private Mono<Void> handleSession(WebSocketSession session) {
        reconnectAttempts = 0;
        log.info("Polymarket RTDS connected");

        Mono<Void> output = session.send(
                outbound.asFlux().map(session::textMessage)
        );

        Mono<Void> input = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .doOnNext(this::handleMessage)
                .then();

        subscribeChainlinkBtc();
        startPing();

        return Mono.zip(output, input).then();
    }

    // ── Subscription ──

    private void subscribeChainlinkBtc() {
        try {
            Map<String, Object> subscription = Map.of(
                    "action", "subscribe",
                    "subscriptions", List.of(
                            Map.of("topic", "crypto_prices_chainlink", "type", "*",
                                    "filters", "{\"symbol\":\"" + CHAINLINK_BTC_SYMBOL + "\"}")
                    )
            );
            String message = objectMapper.writeValueAsString(subscription);
            sendMessage(message);
            log.info("Polymarket RTDS subscribed: topic=crypto_prices_chainlink, symbol={}", CHAINLINK_BTC_SYMBOL);
        } catch (JsonProcessingException e) {
            log.error("Failed to build Chainlink BTC subscription message", e);
        }
    }

    // ── Ping / Send ──

    private void startPing() {
        stopPing();
        pingDisposable = Flux.interval(PING_INTERVAL)
                .doOnNext(tick -> sendMessage("PING"))
                .subscribe();
    }

    private void stopPing() {
        if (pingDisposable != null && !pingDisposable.isDisposed()) {
            pingDisposable.dispose();
        }
    }

    private void sendMessage(String message) {
        Sinks.EmitResult result = outbound.tryEmitNext(message);
        if (result.isFailure()) {
            log.warn("Failed to send RTDS message: {}", result);
        }
    }

    // ── Message handling ──

    private void handleMessage(String raw) {
        if ("PONG".equals(raw)) return;

        try {
            JsonNode json = objectMapper.readTree(raw);

            String topic = json.has("topic") ? json.get("topic").asText() : null;
            if (!"crypto_prices_chainlink".equals(topic)) return;

            JsonNode payload = json.get("payload");
            if (payload == null) return;

            String symbol = payload.has("symbol") ? payload.get("symbol").asText().toLowerCase() : null;
            if (!CHAINLINK_BTC_SYMBOL.equals(symbol)) return;

            BigDecimal price = extractPrice(payload);
            if (price == null) return;

            BigDecimal prev = chainlinkBtcPrice.getAndSet(price);
            lastUpdated.set(Instant.now());
            if (prev == null) {
                log.info("RTDS Chainlink BTC initial price: {}", price);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse RTDS message: {}", e.getMessage());
        }
    }

    /**
     * Extract price from a live update or snapshot.
     */
    private BigDecimal extractPrice(JsonNode payload) {
        if (payload.has("value")) {
            return payload.get("value").decimalValue();
        }
        if (payload.has("data") && payload.get("data").isArray()) {
            JsonNode dataArray = payload.get("data");
            if (!dataArray.isEmpty()) {
                JsonNode lastEntry = dataArray.get(dataArray.size() - 1);
                if (lastEntry.has("value")) {
                    return lastEntry.get("value").decimalValue();
                }
            }
        }
        return null;
    }

    // ── Reconnect ──

    private void scheduleReconnect() {
        if (!running) return;
        reconnectAttempts++;
        int delaySec = Math.min(reconnectAttempts * 2, MAX_RECONNECT_DELAY_SECONDS);
        log.info("Polymarket RTDS reconnecting in {}s (attempt #{})", delaySec, reconnectAttempts);

        Mono.delay(Duration.ofSeconds(delaySec))
                .doOnNext(tick -> connect())
                .subscribe();
    }
}
