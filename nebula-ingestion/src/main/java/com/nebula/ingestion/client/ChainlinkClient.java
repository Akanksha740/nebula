package com.nebula.ingestion.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Provides BTC/USD price from the Chainlink feed via Polymarket RTDS WebSocket.
 * Zero-latency reads from an in-memory cache.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChainlinkClient {

    private final PolymarketWebSocketService rtdsService;

    /**
     * BTC/USD from Chainlink feed.
     */
    public BigDecimal getBtcPrice() {
        return rtdsService.getChainlinkBtcPrice();
    }

    /**
     * Returns true if the RTDS connection is active and prices are fresh.
     */
    public boolean isConnected() {
        return rtdsService.isConnected();
    }
}
