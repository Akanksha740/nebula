package com.nebula.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketDto {

    @JsonProperty("market_id")
    private String marketId;

    @JsonProperty("event_id")
    private String eventId;

    private String slug;

    private String coin;

    @JsonProperty("market_type")
    private String marketType;

    @JsonProperty("start_time")
    private Instant startTime;

    @JsonProperty("end_time")
    private Instant endTime;

    @JsonProperty("coin_price_start")
    private BigDecimal coinPriceStart;

    @JsonProperty("condition_id")
    private String conditionId;

    @JsonProperty("clob_token_up")
    private String clobTokenUp;

    @JsonProperty("clob_token_down")
    private String clobTokenDown;

    private String winner;

    @JsonProperty("final_volume")
    private BigDecimal finalVolume;

    @JsonProperty("final_liquidity")
    private BigDecimal finalLiquidity;

    @JsonProperty("coin_price_end")
    private BigDecimal coinPriceEnd;

    @JsonProperty("is_resolved")
    private Boolean isResolved;

    @JsonProperty("resolved_at")
    private Instant resolvedAt;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;
}
