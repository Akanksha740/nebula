package com.nebula.ingestion.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * One snapshot row as returned by the polybacktest.com backup API:
 *   GET /v2/markets/{marketId}/snapshots?coin=BTC&include_orderbook=true&limit=1000
 *
 * Matches the sample response shape:
 *   { id, time, market_id, btc_price, price_up, price_down, orderbook_up, orderbook_down }
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolyBacktestSnapshot {

    private Long id;
    private Instant time;

    @JsonProperty("market_id")
    private String marketId;

    @JsonProperty("btc_price")
    private BigDecimal btcPrice;

    @JsonProperty("price_up")
    private BigDecimal priceUp;

    @JsonProperty("price_down")
    private BigDecimal priceDown;

    @JsonProperty("orderbook_up")
    private Map<String, Object> orderbookUp;

    @JsonProperty("orderbook_down")
    private Map<String, Object> orderbookDown;
}
