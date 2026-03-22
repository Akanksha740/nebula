package com.nebula.api.controller;

import com.nebula.api.service.ApiAccessService;
import com.nebula.api.service.MarketService;
import com.nebula.common.dto.MarketDto;
import com.nebula.common.dto.MarketWithSnapshotsResponse;
import com.nebula.common.dto.MarketsListResponse;
import com.nebula.common.entity.Coin;
import com.nebula.common.entity.Customer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping("/v1/markets")
@RequiredArgsConstructor
@Tag(name = "Markets", description = "Market data endpoints with pagination and filtering")
public class MarketController {

    private static final Logger log = LoggerFactory.getLogger(MarketController.class);

    private final MarketService marketService;
    private final ApiAccessService apiAccessService;

    @GetMapping
    @Operation(summary = "List all markets with pagination",
               description = "Returns markets sorted by start_time descending (newest first)")
    public ResponseEntity<MarketsListResponse> getMarkets(
            @AuthenticationPrincipal Customer customer,

            @Parameter(description = "Cryptocurrency to query (btc, eth, sol)", required = true)
            @RequestParam String coin,

            @Parameter(description = "Number of results to return (1-100)")
            @RequestParam(defaultValue = "100") int limit,

            @Parameter(description = "Pagination offset")
            @RequestParam(defaultValue = "0") int offset,

            @Parameter(description = "Filter by market type (5m, 15m, 1hr, 4hr, 24hr)")
            @RequestParam(required = false) String market_type,

            @Parameter(description = "Filter by resolution status")
            @RequestParam(required = false) Boolean resolved,

            @Parameter(description = "Filter markets starting after this time (ms epoch or ISO8601)")
            @RequestParam(required = false) String start_time,

            @Parameter(description = "Filter markets starting before this time (ms epoch or ISO8601)")
            @RequestParam(required = false) String end_time) {

        // Parse coin
        Coin coinEnum = parseCoin(coin);

        // Check tier access for non-BTC coins
        apiAccessService.checkCoinAccess(customer, coinEnum);

        // Validate limit
        if (limit < 0) limit = 0;
        if (limit > 100) limit = 100;

        // Validate offset
        if (offset < 0) offset = 0;

        // Parse time filters
        Instant startTimeAfter = parseTime(start_time);
        Instant startTimeBefore = parseTime(end_time);

        log.info("Markets request: coin={}, type={}, limit={}, customer={}, tier={}",
                coin, market_type, limit, customer != null ? customer.getEmail() : "anonymous", customer != null ? customer.getTier() : "N/A");

        MarketsListResponse response = marketService.getMarkets(
                customer, coinEnum, market_type, resolved, startTimeAfter, startTimeBefore, limit, offset);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get market data by slug")
    public ResponseEntity<MarketDto> getMarket(
            @AuthenticationPrincipal Customer customer,
            @PathVariable String slug) {
        Coin coinEnum = coinFromSlug(slug);
        apiAccessService.checkCoinAccess(customer, coinEnum);
        log.info("Market request: slug={}, coin={}, customer={}, tier={}", slug, coinEnum, customer != null ? customer.getEmail() : "anonymous", customer != null ? customer.getTier() : "N/A");
        return ResponseEntity.ok(marketService.getMarketBySlug(slug));
    }

    @GetMapping("/{slug}/snapshots")
    @Operation(summary = "Get market with snapshots by slug")
    public ResponseEntity<MarketWithSnapshotsResponse> getMarketWithSnapshots(
            @AuthenticationPrincipal Customer customer,
            @PathVariable String slug,
            @Parameter(description = "Number of results to return (1-100)")
            @RequestParam(defaultValue = "1000") int limit,

            @Parameter(description = "Pagination offset")
            @RequestParam(defaultValue = "0") int offset,

            @Parameter(description = "Include full orderbook data (default: false for lower latency)")
            @RequestParam(defaultValue = "false") boolean include_orderbook) {
        Coin coinEnum = coinFromSlug(slug);
        apiAccessService.checkCoinAccess(customer, coinEnum);

        // Validate limit
        if (limit < 1) limit = 1;
        if (limit > 1000) limit = 1000;

        // Validate offset
        if (offset < 0) offset = 0;

        log.info("Snapshots request: slug={}, coin={}, limit={}, customer={}, tier={}",
                slug, coinEnum, limit, customer != null ? customer.getEmail() : "anonymous", customer != null ? customer.getTier() : "N/A");
        return ResponseEntity.ok(marketService.getMarketWithSnapshots(customer, slug, limit, offset, include_orderbook));
    }

    @GetMapping("/by-market-id/{marketId}/snapshots")
    @Operation(summary = "Get market with snapshots by Polymarket market ID")
    public ResponseEntity<MarketWithSnapshotsResponse> getMarketWithSnapshotsByMarketId(
            @AuthenticationPrincipal Customer customer,
            @PathVariable String marketId,
            @Parameter(description = "Number of results to return (1-1000)")
            @RequestParam(defaultValue = "1000") int limit,

            @Parameter(description = "Pagination offset")
            @RequestParam(defaultValue = "0") int offset,

            @Parameter(description = "Include full orderbook data (default: false for lower latency)")
            @RequestParam(defaultValue = "false") boolean include_orderbook) {

        // Validate limit
        if (limit < 1) limit = 1;
        if (limit > 1000) limit = 1000;

        // Validate offset
        if (offset < 0) offset = 0;

        log.info("Snapshots by marketId request: marketId={}, limit={}, customer={}, tier={}",
                marketId, limit, customer != null ? customer.getEmail() : "anonymous", customer != null ? customer.getTier() : "N/A");
        return ResponseEntity.ok(marketService.getMarketWithSnapshotsByMarketId(customer, marketId, limit, offset, include_orderbook));
    }

    private Coin coinFromSlug(String slug) {
        Coin coin = Coin.fromSlug(slug);
        if (coin == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not determine coin from slug: " + slug);
        }
        return coin;
    }

    private Coin parseCoin(String coin) {
        if (coin == null || coin.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "coin parameter is required");
        }
        try {
            return Coin.valueOf(coin.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported coin: " + coin + ". Only BTC and ETH are supported as of now");
        }
    }

    private Instant parseTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            // Try parsing as epoch milliseconds
            long epoch = Long.parseLong(time);
            return Instant.ofEpochMilli(epoch);
        } catch (NumberFormatException e) {
            // Try parsing as ISO8601
            try {
                return Instant.parse(time);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
