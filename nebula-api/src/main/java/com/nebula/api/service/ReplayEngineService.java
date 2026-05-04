package com.nebula.api.service;

import com.nebula.api.repository.MarketRepository;
import com.nebula.api.repository.MarketSnapshotRepository;
import com.nebula.common.dto.MarketDto;
import com.nebula.common.dto.ReplayResultDto;
import com.nebula.common.dto.ReplayResultDto.*;
import com.nebula.common.dto.request.ReplayRequest;
import com.nebula.common.dto.request.ReplayRequest.Condition;
import com.nebula.common.entity.Customer;
import com.nebula.common.entity.Market;
import com.nebula.common.entity.MarketSnapshot;
import com.nebula.common.exception.NebulaException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReplayEngineService {

    private static final Logger log = LoggerFactory.getLogger(ReplayEngineService.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_SNAPSHOTS = 50_000;

    private final MarketRepository marketRepository;
    private final MarketSnapshotRepository snapshotRepository;
    private final ApiAccessService apiAccessService;

    private static final Set<String> VALID_FIELDS = Set.of(
        "price_up", "price_down", "coin_price", "spread", "time_remaining_pct"
    );

    private static final Set<String> VALID_OPERATORS = Set.of(
        "<", ">", "<=", ">=", "==", "crosses_above", "crosses_below"
    );

    public ReplayResultDto replay(Customer customer, ReplayRequest request) {
        validateRequest(request);

        Market market = marketRepository.findBySlug(request.getMarketSlug())
                .orElseThrow(() -> new NebulaException("Market not found: " + request.getMarketSlug(), "MARKET_NOT_FOUND", 404));

        if (!market.getResolved()) {
            throw new NebulaException("Can only replay resolved markets", "MARKET_NOT_RESOLVED", 400);
        }

        apiAccessService.checkCoinAccess(customer, market.getCoin());

        // Load all snapshots in chronological order
        List<MarketSnapshot> snapshots = snapshotRepository.findByMarketIdOrderByTimeAsc(market.getId());
        if (snapshots.isEmpty()) {
            throw new NebulaException("No snapshot data available for this market", "NO_DATA", 404);
        }
        if (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots = snapshots.subList(0, MAX_SNAPSHOTS);
        }

        log.info("Replaying strategy on market {} ({} snapshots) for customer {}",
                market.getSlug(), snapshots.size(), customer.getEmail());

        // Run the engine
        EngineState state = new EngineState(request, market, snapshots);
        runEngine(state);

        return buildResult(market, request, state);
    }

    private void validateRequest(ReplayRequest request) {
        String side = request.getSide();
        if (!"UP".equalsIgnoreCase(side) && !"DOWN".equalsIgnoreCase(side)) {
            throw new NebulaException("Side must be UP or DOWN", "INVALID_SIDE", 400);
        }

        for (Condition c : request.getEntryConditions()) {
            validateCondition(c);
        }
        for (Condition c : request.getExitConditions()) {
            validateCondition(c);
        }

        if (request.getOrderType() == null || request.getOrderType().isBlank()) {
            request.setOrderType("MARKET");
        }
        if (!"MARKET".equalsIgnoreCase(request.getOrderType()) && !"LIMIT".equalsIgnoreCase(request.getOrderType())) {
            throw new NebulaException("orderType must be MARKET or LIMIT", "INVALID_ORDER_TYPE", 400);
        }

        if (request.getMaxLossPercent() == null) {
            request.setMaxLossPercent(new BigDecimal("50"));
        }
    }

    private void validateCondition(Condition c) {
        if (!VALID_FIELDS.contains(c.getField())) {
            throw new NebulaException("Invalid condition field: " + c.getField() + ". Valid: " + VALID_FIELDS, "INVALID_FIELD", 400);
        }
        if (!VALID_OPERATORS.contains(c.getOperator())) {
            throw new NebulaException("Invalid operator: " + c.getOperator() + ". Valid: " + VALID_OPERATORS, "INVALID_OPERATOR", 400);
        }
    }

    // ── Engine core ──

    private void runEngine(EngineState state) {
        for (int i = 0; i < state.snapshots.size(); i++) {
            MarketSnapshot snap = state.snapshots.get(i);
            MarketSnapshot prev = i > 0 ? state.snapshots.get(i - 1) : null;

            BigDecimal price = getTradePrice(snap, state.side);
            BigDecimal coinPrice = snap.getCoinPrice();
            BigDecimal timeRemainingPct = calcTimeRemainingPct(snap.getTime(), state.market);
            BigDecimal spread = calcSpread(snap);

            TickData tick = new TickData(price, snap.getPriceUp(), snap.getPriceDown(), coinPrice, spread, timeRemainingPct);
            TickData prevTick = null;
            if (prev != null) {
                BigDecimal prevPrice = getTradePrice(prev, state.side);
                BigDecimal prevSpread = calcSpread(prev);
                BigDecimal prevTimeRemPct = calcTimeRemainingPct(prev.getTime(), state.market);
                prevTick = new TickData(prevPrice, prev.getPriceUp(), prev.getPriceDown(), prev.getCoinPrice(), prevSpread, prevTimeRemPct);
            }

            if (state.inPosition) {
                // Check max loss
                BigDecimal unrealizedPnl = price.subtract(state.entryPrice).multiply(state.positionSize, MC);
                BigDecimal drawdownPct = unrealizedPnl.divide(state.entryPrice.multiply(state.positionSize, MC), 6, RoundingMode.HALF_UP)
                        .multiply(HUNDRED, MC).abs();

                if (unrealizedPnl.compareTo(BigDecimal.ZERO) < 0 && drawdownPct.compareTo(state.maxLossPercent) >= 0) {
                    closePosition(state, snap, price, coinPrice, "MAX_LOSS");
                }
                // Check exit conditions (any true → exit)
                else if (evaluateConditions(state.exitConditions, tick, prevTick, true)) {
                    closePosition(state, snap, price, coinPrice, "SIGNAL");
                }
            } else {
                // Check entry conditions (all true → enter)
                if (evaluateConditions(state.entryConditions, tick, prevTick, false)) {
                    BigDecimal fillPrice = simulateFill(snap, state.side, state.positionSize, true);
                    BigDecimal slippage = fillPrice.subtract(price).abs();
                    openPosition(state, snap, fillPrice, slippage, coinPrice);
                }
            }

            // Record PnL point (sample every N ticks for large datasets)
            if (state.snapshots.size() <= 2000 || i % (state.snapshots.size() / 2000 + 1) == 0 || i == state.snapshots.size() - 1) {
                BigDecimal unrealized = BigDecimal.ZERO;
                if (state.inPosition) {
                    unrealized = price.subtract(state.entryPrice).multiply(state.positionSize, MC);
                }
                state.pnlCurve.add(PnlPoint.builder()
                        .time(snap.getTime().toString())
                        .pnl(state.cumulativePnl)
                        .unrealizedPnl(unrealized)
                        .price(price)
                        .coinPrice(coinPrice)
                        .inPosition(state.inPosition)
                        .build());
            }

            // Track max drawdown
            BigDecimal totalEquity = state.cumulativePnl;
            if (state.inPosition) {
                totalEquity = totalEquity.add(price.subtract(state.entryPrice).multiply(state.positionSize, MC));
            }
            if (totalEquity.compareTo(state.peakEquity) > 0) {
                state.peakEquity = totalEquity;
            }
            BigDecimal dd = state.peakEquity.subtract(totalEquity);
            if (dd.compareTo(state.maxDrawdown) > 0) {
                state.maxDrawdown = dd;
            }
        }

        // Force close at market end if still in position
        if (state.inPosition) {
            MarketSnapshot last = state.snapshots.get(state.snapshots.size() - 1);
            BigDecimal lastPrice = getTradePrice(last, state.side);
            closePosition(state, last, lastPrice, last.getCoinPrice(), "MARKET_END");
        }
    }

    private boolean evaluateConditions(List<Condition> conditions, TickData tick, TickData prev, boolean anyMode) {
        for (Condition c : conditions) {
            boolean result = evaluateCondition(c, tick, prev);
            if (anyMode && result) return true;
            if (!anyMode && !result) return false;
        }
        return !anyMode; // all-mode: true if no condition failed; any-mode: false if none matched
    }

    private boolean evaluateCondition(Condition c, TickData tick, TickData prev) {
        BigDecimal fieldValue = getFieldValue(c.getField(), tick);
        BigDecimal threshold = c.getValue();

        return switch (c.getOperator()) {
            case "<" -> fieldValue.compareTo(threshold) < 0;
            case ">" -> fieldValue.compareTo(threshold) > 0;
            case "<=" -> fieldValue.compareTo(threshold) <= 0;
            case ">=" -> fieldValue.compareTo(threshold) >= 0;
            case "==" -> fieldValue.compareTo(threshold) == 0;
            case "crosses_above" -> {
                if (prev == null) yield false;
                BigDecimal prevValue = getFieldValue(c.getField(), prev);
                yield prevValue.compareTo(threshold) <= 0 && fieldValue.compareTo(threshold) > 0;
            }
            case "crosses_below" -> {
                if (prev == null) yield false;
                BigDecimal prevValue = getFieldValue(c.getField(), prev);
                yield prevValue.compareTo(threshold) >= 0 && fieldValue.compareTo(threshold) < 0;
            }
            default -> false;
        };
    }

    private BigDecimal getFieldValue(String field, TickData tick) {
        return switch (field) {
            case "price_up" -> tick.priceUp != null ? tick.priceUp : BigDecimal.ZERO;
            case "price_down" -> tick.priceDown != null ? tick.priceDown : BigDecimal.ZERO;
            case "coin_price" -> tick.coinPrice != null ? tick.coinPrice : BigDecimal.ZERO;
            case "spread" -> tick.spread;
            case "time_remaining_pct" -> tick.timeRemainingPct;
            default -> BigDecimal.ZERO;
        };
    }

    private BigDecimal getTradePrice(MarketSnapshot snap, String side) {
        return "UP".equalsIgnoreCase(side)
                ? (snap.getPriceUp() != null ? snap.getPriceUp() : BigDecimal.ZERO)
                : (snap.getPriceDown() != null ? snap.getPriceDown() : BigDecimal.ZERO);
    }

    /**
     * Simulate a fill by walking the order book. For a buy, walk the asks;
     * for a sell, walk the bids. Returns VWAP fill price.
     */
    @SuppressWarnings("unchecked")
    private BigDecimal simulateFill(MarketSnapshot snap, String side, BigDecimal shares, boolean isBuy) {
        Map<String, Object> book = "UP".equalsIgnoreCase(side) ? snap.getOrderbookUp() : snap.getOrderbookDown();
        if (book == null) {
            return getTradePrice(snap, side); // no book → use mid price
        }

        String levelKey = isBuy ? "asks" : "bids";
        Object levelsObj = book.get(levelKey);
        if (!(levelsObj instanceof List)) {
            return getTradePrice(snap, side);
        }

        List<Map<String, Object>> levels = (List<Map<String, Object>>) levelsObj;
        BigDecimal remaining = shares;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (Map<String, Object> level : levels) {
            BigDecimal levelPrice = new BigDecimal(level.get("price").toString());
            BigDecimal levelSize = new BigDecimal(level.get("size").toString());

            BigDecimal fillQty = remaining.min(levelSize);
            totalCost = totalCost.add(fillQty.multiply(levelPrice, MC));
            remaining = remaining.subtract(fillQty);

            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
        }

        BigDecimal filled = shares.subtract(remaining);
        if (filled.compareTo(BigDecimal.ZERO) <= 0) {
            return getTradePrice(snap, side);
        }
        return totalCost.divide(filled, 6, RoundingMode.HALF_UP);
    }

    private void openPosition(EngineState state, MarketSnapshot snap, BigDecimal fillPrice, BigDecimal slippage, BigDecimal coinPrice) {
        state.inPosition = true;
        state.entryPrice = fillPrice;
        state.entryTime = snap.getTime();

        state.trades.add(TradeEvent.builder()
                .type("ENTRY")
                .reason("SIGNAL")
                .time(snap.getTime().toString())
                .price(getTradePrice(snap, state.side))
                .fillPrice(fillPrice)
                .shares(state.positionSize)
                .slippage(slippage)
                .positionPnl(BigDecimal.ZERO)
                .cumulativePnl(state.cumulativePnl)
                .coinPrice(coinPrice)
                .build());
    }

    private void closePosition(EngineState state, MarketSnapshot snap, BigDecimal exitPrice, BigDecimal coinPrice, String reason) {
        BigDecimal fillPrice = simulateFill(snap, state.side, state.positionSize, false);
        BigDecimal slippage = fillPrice.subtract(exitPrice).abs();
        BigDecimal tradePnl = fillPrice.subtract(state.entryPrice).multiply(state.positionSize, MC);

        state.cumulativePnl = state.cumulativePnl.add(tradePnl);
        state.inPosition = false;

        if (tradePnl.compareTo(BigDecimal.ZERO) > 0) state.winCount++;
        else state.loseCount++;

        if (tradePnl.compareTo(state.bestTrade) > 0) state.bestTrade = tradePnl;
        if (tradePnl.compareTo(state.worstTrade) < 0) state.worstTrade = tradePnl;

        state.totalTradesPnl = state.totalTradesPnl.add(tradePnl);
        state.tradeCount++;

        // Track time in position
        if (state.entryTime != null) {
            state.timeInPosition += Duration.between(state.entryTime, snap.getTime()).toMillis();
        }

        state.trades.add(TradeEvent.builder()
                .type("EXIT")
                .reason(reason)
                .time(snap.getTime().toString())
                .price(exitPrice)
                .fillPrice(fillPrice)
                .shares(state.positionSize)
                .slippage(slippage)
                .positionPnl(tradePnl)
                .cumulativePnl(state.cumulativePnl)
                .coinPrice(coinPrice)
                .build());
    }

    private BigDecimal calcTimeRemainingPct(Instant snapTime, Market market) {
        if (market.getStartTime() == null || market.getEndTime() == null) return BigDecimal.ZERO;
        long total = Duration.between(market.getStartTime(), market.getEndTime()).toMillis();
        if (total <= 0) return BigDecimal.ZERO;
        long remaining = Duration.between(snapTime, market.getEndTime()).toMillis();
        if (remaining < 0) remaining = 0;
        return BigDecimal.valueOf(remaining).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP).multiply(HUNDRED, MC);
    }

    private BigDecimal calcSpread(MarketSnapshot snap) {
        if (snap.getPriceUp() == null || snap.getPriceDown() == null) return BigDecimal.ZERO;
        return BigDecimal.ONE.subtract(snap.getPriceUp()).subtract(snap.getPriceDown()).abs();
    }

    // ── Result builder ──

    private ReplayResultDto buildResult(Market market, ReplayRequest request, EngineState state) {
        BigDecimal initialCapital = state.positionSize; // approximate
        BigDecimal pnlPct = BigDecimal.ZERO;
        if (initialCapital.compareTo(BigDecimal.ZERO) > 0) {
            pnlPct = state.cumulativePnl.divide(initialCapital, 6, RoundingMode.HALF_UP).multiply(HUNDRED, MC);
        }

        BigDecimal maxDdPct = BigDecimal.ZERO;
        if (state.peakEquity.compareTo(BigDecimal.ZERO) > 0) {
            maxDdPct = state.maxDrawdown.divide(state.peakEquity.max(initialCapital), 6, RoundingMode.HALF_UP).multiply(HUNDRED, MC);
        }

        BigDecimal winRate = BigDecimal.ZERO;
        BigDecimal avgPnl = BigDecimal.ZERO;
        if (state.tradeCount > 0) {
            winRate = BigDecimal.valueOf(state.winCount).divide(BigDecimal.valueOf(state.tradeCount), 4, RoundingMode.HALF_UP).multiply(HUNDRED, MC);
            avgPnl = state.totalTradesPnl.divide(BigDecimal.valueOf(state.tradeCount), 6, RoundingMode.HALF_UP);
        }

        long totalMarketDuration = 1;
        if (market.getStartTime() != null && market.getEndTime() != null) {
            totalMarketDuration = Math.max(1, Duration.between(market.getStartTime(), market.getEndTime()).toMillis());
        }
        BigDecimal timeInPosPct = BigDecimal.valueOf(state.timeInPosition)
                .divide(BigDecimal.valueOf(totalMarketDuration), 4, RoundingMode.HALF_UP)
                .multiply(HUNDRED, MC);

        return ReplayResultDto.builder()
                .market(toMarketDto(market))
                .strategy(StrategySummary.builder()
                        .side(request.getSide().toUpperCase())
                        .positionSize(request.getPositionSize())
                        .orderType(request.getOrderType())
                        .entryConditionCount(request.getEntryConditions().size())
                        .exitConditionCount(request.getExitConditions().size())
                        .build())
                .performance(PerformanceSummary.builder()
                        .totalPnl(state.cumulativePnl.setScale(4, RoundingMode.HALF_UP))
                        .totalPnlPercent(pnlPct.setScale(2, RoundingMode.HALF_UP))
                        .maxDrawdown(state.maxDrawdown.setScale(4, RoundingMode.HALF_UP))
                        .maxDrawdownPercent(maxDdPct.setScale(2, RoundingMode.HALF_UP))
                        .totalTrades(state.tradeCount)
                        .winningTrades(state.winCount)
                        .losingTrades(state.loseCount)
                        .winRate(winRate.setScale(2, RoundingMode.HALF_UP))
                        .avgTradePnl(avgPnl.setScale(4, RoundingMode.HALF_UP))
                        .bestTrade(state.bestTrade.setScale(4, RoundingMode.HALF_UP))
                        .worstTrade(state.worstTrade.setScale(4, RoundingMode.HALF_UP))
                        .timeInPositionPct(timeInPosPct.setScale(2, RoundingMode.HALF_UP))
                        .finalMarketOutcome(market.getWinner() != null ? market.getWinner() : "UNKNOWN")
                        .build())
                .trades(state.trades)
                .pnlCurve(state.pnlCurve)
                .totalSnapshots(state.snapshots.size())
                .build();
    }

    private MarketDto toMarketDto(Market m) {
        return MarketDto.builder()
                .marketId(m.getMarketId())
                .eventId(m.getEventId())
                .slug(m.getSlug())
                .coin(m.getCoin().name())
                .marketType(m.getMarketType())
                .startTime(m.getStartTime())
                .endTime(m.getEndTime())
                .coinPriceStart(m.getCoinPriceStart())
                .coinPriceEnd(m.getCoinPriceEnd())
                .winner(m.getWinner())
                .finalVolume(m.getFinalVolume())
                .finalLiquidity(m.getFinalLiquidity())
                .isResolved(m.getResolved())
                .resolvedAt(m.getResolvedAt())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }

    // ── Internal state ──

    private record TickData(BigDecimal price, BigDecimal priceUp, BigDecimal priceDown,
                            BigDecimal coinPrice, BigDecimal spread, BigDecimal timeRemainingPct) {}

    private static class EngineState {
        final String side;
        final BigDecimal positionSize;
        final BigDecimal maxLossPercent;
        final List<Condition> entryConditions;
        final List<Condition> exitConditions;
        final Market market;
        final List<MarketSnapshot> snapshots;

        boolean inPosition = false;
        BigDecimal entryPrice = BigDecimal.ZERO;
        Instant entryTime = null;
        BigDecimal cumulativePnl = BigDecimal.ZERO;
        BigDecimal peakEquity = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal totalTradesPnl = BigDecimal.ZERO;
        BigDecimal bestTrade = BigDecimal.ZERO;
        BigDecimal worstTrade = BigDecimal.ZERO;
        int tradeCount = 0;
        int winCount = 0;
        int loseCount = 0;
        long timeInPosition = 0;

        List<TradeEvent> trades = new ArrayList<>();
        List<PnlPoint> pnlCurve = new ArrayList<>();

        EngineState(ReplayRequest req, Market market, List<MarketSnapshot> snapshots) {
            this.side = req.getSide().toUpperCase();
            this.positionSize = req.getPositionSize();
            this.maxLossPercent = req.getMaxLossPercent();
            this.entryConditions = req.getEntryConditions();
            this.exitConditions = req.getExitConditions();
            this.market = market;
            this.snapshots = snapshots;
        }
    }
}
