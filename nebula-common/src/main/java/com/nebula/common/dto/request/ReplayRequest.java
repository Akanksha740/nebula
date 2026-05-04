package com.nebula.common.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request to replay a strategy against a historical market.
 *
 * Strategy DSL:
 *   - side: "UP" or "DOWN" (which token to trade)
 *   - entryConditions: list of conditions that ALL must be true to enter
 *   - exitConditions: list of conditions where ANY being true triggers exit
 *   - positionSize: number of shares per trade (1-1000)
 *   - maxLossPercent: close position if drawdown exceeds this (1-100)
 *   - orderType: "MARKET" or "LIMIT"
 *
 * Condition fields:
 *   - field: "price_up", "price_down", "coin_price", "spread", "time_remaining_pct"
 *   - operator: "<", ">", "<=", ">=", "==", "crosses_above", "crosses_below"
 *   - value: numeric threshold
 */
@Data
public class ReplayRequest {

    @NotBlank
    private String marketSlug;

    @NotBlank
    private String side; // "UP" or "DOWN"

    @Valid
    @NotNull
    private List<Condition> entryConditions;

    @Valid
    @NotNull
    private List<Condition> exitConditions;

    @NotNull
    @DecimalMin("1")
    @DecimalMax("10000")
    private BigDecimal positionSize;

    @DecimalMin("1")
    @DecimalMax("100")
    private BigDecimal maxLossPercent;

    private String orderType; // "MARKET" (default) or "LIMIT"

    @Data
    public static class Condition {
        @NotBlank
        private String field;

        @NotBlank
        private String operator;

        @NotNull
        private BigDecimal value;
    }
}
