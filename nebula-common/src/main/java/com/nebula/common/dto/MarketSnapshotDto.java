package com.nebula.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MarketSnapshotDto {

    private Instant time;

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
