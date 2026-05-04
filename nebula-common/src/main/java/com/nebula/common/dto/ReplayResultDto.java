package com.nebula.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of replaying a strategy against a historical market.
 */
@Data
@Builder
public class ReplayResultDto {

    private MarketDto market;
    private StrategySummary strategy;
    private PerformanceSummary performance;
    private List<TradeEvent> trades;

    @JsonProperty("pnl_curve")
    private List<PnlPoint> pnlCurve;

    @JsonProperty("total_snapshots")
    private long totalSnapshots;

    @Data
    @Builder
    public static class StrategySummary {
        private String side;
        private BigDecimal positionSize;
        private String orderType;
        private int entryConditionCount;
        private int exitConditionCount;
    }

    @Data
    @Builder
    public static class PerformanceSummary {
        @JsonProperty("total_pnl")
        private BigDecimal totalPnl;

        @JsonProperty("total_pnl_percent")
        private BigDecimal totalPnlPercent;

        @JsonProperty("max_drawdown")
        private BigDecimal maxDrawdown;

        @JsonProperty("max_drawdown_percent")
        private BigDecimal maxDrawdownPercent;

        @JsonProperty("total_trades")
        private int totalTrades;

        @JsonProperty("winning_trades")
        private int winningTrades;

        @JsonProperty("losing_trades")
        private int losingTrades;

        @JsonProperty("win_rate")
        private BigDecimal winRate;

        @JsonProperty("avg_trade_pnl")
        private BigDecimal avgTradePnl;

        @JsonProperty("best_trade")
        private BigDecimal bestTrade;

        @JsonProperty("worst_trade")
        private BigDecimal worstTrade;

        @JsonProperty("time_in_position_pct")
        private BigDecimal timeInPositionPct;

        @JsonProperty("final_market_outcome")
        private String finalMarketOutcome; // "UP" or "DOWN"
    }

    @Data
    @Builder
    public static class TradeEvent {
        private String type; // "ENTRY" or "EXIT"
        private String reason; // "SIGNAL", "MAX_LOSS", "MARKET_END"
        private String time;
        private BigDecimal price;

        @JsonProperty("fill_price")
        private BigDecimal fillPrice;

        private BigDecimal shares;

        @JsonProperty("slippage")
        private BigDecimal slippage;

        @JsonProperty("position_pnl")
        private BigDecimal positionPnl;

        @JsonProperty("cumulative_pnl")
        private BigDecimal cumulativePnl;

        @JsonProperty("coin_price")
        private BigDecimal coinPrice;
    }

    @Data
    @Builder
    public static class PnlPoint {
        private String time;
        private BigDecimal pnl;

        @JsonProperty("unrealized_pnl")
        private BigDecimal unrealizedPnl;

        @JsonProperty("price")
        private BigDecimal price;

        @JsonProperty("coin_price")
        private BigDecimal coinPrice;

        @JsonProperty("in_position")
        private boolean inPosition;
    }
}
