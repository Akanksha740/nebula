package com.nebula.ingestion.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Provides BTC/USD price from the Chainlink feed via Polymarket RTDS WebSocket.
 * Reads are served from an in-memory cache maintained by PolymarketWebSocketService.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChainlinkClient {

    private final PolymarketWebSocketService rtdsService;

    public BigDecimal getBtcPrice() {
        return rtdsService.getChainlinkBtcPrice();
    }

    public boolean isConnected() {
        return rtdsService.isConnected();
    }
}
