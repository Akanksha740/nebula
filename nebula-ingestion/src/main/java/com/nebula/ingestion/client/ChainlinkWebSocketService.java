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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maintains a persistent WebSocket connection to an Ethereum node.
 * Subscribes to new block headers (newHeads) and fetches Chainlink
 * latestRoundData() on each block to keep BTC/USD and ETH/USD prices fresh.
 *
 * Prices are cached in AtomicReferences for zero-latency reads by the
 * ingestion service.
 */
@Service
@Slf4j
public class ChainlinkWebSocketService {

    @Value("${chainlink.ws-url:wss://ethereum-rpc.publicnode.com}")
    private String wsUrl;

    @Value("${chainlink.btc-usd-feed:0xF4030086522a5bEEa4988F8cA5B36dbC97BeE88c}")
    private String btcUsdFeed;

    @Value("${chainlink.eth-usd-feed:0x5f4eC3Df9cbd43714FE2740f5E3616155c5b8419}")
    private String ethUsdFeed;

    private static final String LATEST_ROUND_DATA = "0xfeaf968c";
    private static final BigDecimal CHAINLINK_DECIMALS = new BigDecimal("100000000");
    private static final int MAX_RECONNECT_DELAY_SECONDS = 30;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<BigDecimal> cachedBtcPrice = new AtomicReference<>();
    private final AtomicReference<BigDecimal> cachedEthPrice = new AtomicReference<>();
    private final AtomicReference<Instant> lastBtcUpdate = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> lastEthUpdate = new AtomicReference<>(Instant.EPOCH);
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, String> pendingRequests = new ConcurrentHashMap<>();

    private volatile Sinks.Many<String> outbound;
    private volatile Disposable connectionDisposable;
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
        if (connectionDisposable != null && !connectionDisposable.isDisposed()) {
            connectionDisposable.dispose();
        }
    }

    /**
     * Get the latest cached BTC/USD price. Returns null if not yet available.
     */
    public BigDecimal getBtcPrice() {
        return cachedBtcPrice.get();
    }

    /**
     * Get the latest cached ETH/USD price. Returns null if not yet available.
     */
    public BigDecimal getEthPrice() {
        return cachedEthPrice.get();
    }

    /**
     * Returns true if the WebSocket connection is active and prices are fresh
     * (updated within the last 60 seconds).
     */
    public boolean isConnected() {
        Instant now = Instant.now();
        boolean btcFresh = Duration.between(lastBtcUpdate.get(), now).getSeconds() < 60;
        boolean ethFresh = Duration.between(lastEthUpdate.get(), now).getSeconds() < 60;
        return btcFresh || ethFresh;
    }

    private void connect() {
        if (!running) return;

        outbound = Sinks.many().multicast().onBackpressureBuffer(256);

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(10));

        ReactorNettyWebSocketClient wsClient = new ReactorNettyWebSocketClient(httpClient);

        log.info("Chainlink WS connecting to {}", wsUrl);

        connectionDisposable = wsClient.execute(URI.create(wsUrl), this::handleSession)
                .doOnTerminate(() -> {
                    if (running) {
                        log.warn("Chainlink WS connection terminated, scheduling reconnect");
                        scheduleReconnect();
                    }
                })
                .doOnError(e -> {
                    if (running) {
                        log.warn("Chainlink WS error: {}", e.getMessage());
                    }
                })
                .subscribe();
    }

    private Mono<Void> handleSession(WebSocketSession session) {
        reconnectAttempts = 0;
        log.info("Chainlink WS connected");

        // Send outbound messages from the sink
        Mono<Void> output = session.send(
                outbound.asFlux().map(session::textMessage)
        );

        // Process inbound messages
        Mono<Void> input = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .doOnNext(this::handleMessage)
                .then();

        // After connecting, subscribe to newHeads and fetch initial prices
        subscribeNewHeads();
        fetchAllPrices();

        return Mono.zip(output, input).then();
    }

    private void subscribeNewHeads() {
        int id = requestIdCounter.getAndIncrement();
        String request = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"eth_subscribe\",\"params\":[\"newHeads\"]}",
                id);
        pendingRequests.put(id, "subscribe");
        sendMessage(request);
    }

    private void fetchAllPrices() {
        fetchPrice(btcUsdFeed, "btc");
        fetchPrice(ethUsdFeed, "eth");
    }

    private void fetchPrice(String feedAddress, String label) {
        int id = requestIdCounter.getAndIncrement();
        String request = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"eth_call\",\"params\":[{\"to\":\"%s\",\"data\":\"%s\"},\"latest\"]}",
                id, feedAddress, LATEST_ROUND_DATA);
        pendingRequests.put(id, label);
        sendMessage(request);
    }

    private void sendMessage(String message) {
        Sinks.EmitResult result = outbound.tryEmitNext(message);
        if (result.isFailure()) {
            log.warn("Failed to send WS message: {}", result);
        }
    }

    private void handleMessage(String raw) {
        try {
            JsonNode json = objectMapper.readTree(raw);

            // Check if this is a subscription notification (newHeads)
            if (json.has("method") && "eth_subscription".equals(json.get("method").asText())) {
                handleSubscriptionNotification(json);
                return;
            }

            // Otherwise it's a response to one of our requests
            if (json.has("id") && !json.get("id").isNull()) {
                int id = json.get("id").asInt();
                String label = pendingRequests.remove(id);
                if (label == null) return;

                if ("subscribe".equals(label)) {
                    String subId = json.has("result") ? json.get("result").asText() : "unknown";
                    log.info("Chainlink WS subscribed to newHeads (subId: {})", subId);
                    return;
                }

                // eth_call response — parse the price
                if (json.has("result")) {
                    String hexResult = json.get("result").asText();
                    BigDecimal price = decodeLatestRoundData(hexResult);
                    if (price != null) {
                        updatePrice(label, price);
                    }
                } else if (json.has("error")) {
                    log.warn("Chainlink WS RPC error for {}: {}", label, json.get("error"));
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse Chainlink WS message: {}", e.getMessage());
        }
    }

    private void handleSubscriptionNotification(JsonNode json) {
        // New block arrived — fetch fresh prices
        JsonNode params = json.get("params");
        if (params != null && params.has("result")) {
            JsonNode blockResult = params.get("result");
            String blockNumber = blockResult.has("number") ? blockResult.get("number").asText() : "?";
            log.debug("New block {}, refreshing Chainlink prices", blockNumber);
        }
        fetchAllPrices();
    }

    private BigDecimal decodeLatestRoundData(String hexResult) {
        if (hexResult == null || hexResult.length() < 130) {
            return null;
        }
        try {
            String hex = hexResult.startsWith("0x") ? hexResult.substring(2) : hexResult;
            // answer is the 2nd 32-byte slot (bytes 64-128 in hex)
            String answerHex = hex.substring(64, 128);
            BigInteger answerRaw = new BigInteger(answerHex, 16);
            return new BigDecimal(answerRaw).divide(CHAINLINK_DECIMALS, MathContext.DECIMAL64);
        } catch (Exception e) {
            log.warn("Failed to decode Chainlink price: {}", e.getMessage());
            return null;
        }
    }

    private void updatePrice(String label, BigDecimal price) {
        Instant now = Instant.now();
        if ("btc".equals(label)) {
            BigDecimal prev = cachedBtcPrice.getAndSet(price);
            lastBtcUpdate.set(now);
            if (prev == null || prev.compareTo(price) != 0) {
                log.info("Chainlink BTC/USD updated: {}", price);
            }
        } else if ("eth".equals(label)) {
            BigDecimal prev = cachedEthPrice.getAndSet(price);
            lastEthUpdate.set(now);
            if (prev == null || prev.compareTo(price) != 0) {
                log.info("Chainlink ETH/USD updated: {}", price);
            }
        }
    }

    private void scheduleReconnect() {
        if (!running) return;
        reconnectAttempts++;
        int delaySec = Math.min(reconnectAttempts * 2, MAX_RECONNECT_DELAY_SECONDS);
        log.info("Chainlink WS reconnecting in {}s (attempt #{})", delaySec, reconnectAttempts);

        Mono.delay(Duration.ofSeconds(delaySec))
                .doOnNext(tick -> connect())
                .subscribe();
    }
}
