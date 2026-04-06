package com.nebula.ingestion.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Provides Chainlink BTC/USD and ETH/USD prices by reading from the
 * WebSocket service's in-memory cache. Reads are instant (no network call).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChainlinkClient {

    private final ChainlinkWebSocketService webSocketService;

    /**
     * Get latest BTC/USD price from the WebSocket cache.
     */
    public BigDecimal getBtcPrice() {
        return webSocketService.getBtcPrice();
    }

    /**
     * Get latest ETH/USD price from the WebSocket cache.
     */
    public BigDecimal getEthPrice() {
        return webSocketService.getEthPrice();
    }

    /**
     * Returns true if the WebSocket connection is active and prices are fresh.
     */
    public boolean isConnected() {
        return webSocketService.isConnected();
    }
}
