package com.nebula.ingestion.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * One snapshot row as returned by the polybacktest.com backup API:
 *   GET /v2/markets/{marketId}/snapshots?coin={BTC|ETH|SOL}&include_orderbook=true&limit=1000
 *
 * Sample BTC response shape:
 *   { id, time, market_id, btc_price, price_up, price_down, orderbook_up, orderbook_down }
 *
 * For ETH/SOL markets the reference-price field is named {@code eth_price} or
 * {@code sol_price} respectively. {@link #coinPrice} maps any of those via
 * {@code @JsonAlias} so we don't silently store null when switching coins.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolyBacktestSnapshot {

    private Long id;
    private Instant time;

    @JsonProperty("market_id")
    private String marketId;

    /**
     * Reference price of the underlying coin at this snapshot's time.
     * Polybacktest names the field after the coin (btc_price / eth_price /
     * sol_price). We accept all three so a single DTO works across coins.
     */
    @JsonAlias({"btc_price", "eth_price", "sol_price", "coin_price"})
    private BigDecimal coinPrice;

    @JsonProperty("price_up")
    private BigDecimal priceUp;

    @JsonProperty("price_down")
    private BigDecimal priceDown;

    @JsonProperty("orderbook_up")
    private Map<String, Object> orderbookUp;

    @JsonProperty("orderbook_down")
    private Map<String, Object> orderbookDown;
}
