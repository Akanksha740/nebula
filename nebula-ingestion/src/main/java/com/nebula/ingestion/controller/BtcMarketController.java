package com.nebula.ingestion.controller;

import com.nebula.common.dto.response.ApiResponse;
import com.nebula.common.entity.Coin;
import com.nebula.common.entity.Market;
import com.nebula.ingestion.service.BtcMarketIngestionService;
import com.nebula.ingestion.service.SnapshotBackupService;
import com.nebula.ingestion.service.SnapshotBackupService.BackfillResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple controller for BTC market ingestion - POC
 */
@RestController
@RequestMapping("/api/markets")
@RequiredArgsConstructor
@Slf4j
public class BtcMarketController {

    private final BtcMarketIngestionService btcMarketIngestionService;
    private final SnapshotBackupService snapshotBackupService;

    /**
     * Get tracking status
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "activeCount", btcMarketIngestionService.getActiveMarketCount(),
            "activeMarkets", btcMarketIngestionService.getActiveMarketSlugs(),
            "timestamp", Instant.now().toString()
        )));
    }


    /**
     * Trigger immediate snapshot
     */
    @PostMapping("/snapshot")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerSnapshot() {
        btcMarketIngestionService.snapshotActiveMarkets();

        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "message", "Snapshot triggered",
            "activeCount", btcMarketIngestionService.getActiveMarketCount()
        )));
    }

    /**
     * Ad-hoc per-slug backfill: for each slug, ensure the market exists in
     * our DB (creating it from Polymarket gamma-api metadata if missing) and
     * then pull the historical snapshot history from polybacktest. Useful
     * for filling gaps left by ingestion downtime.
     *
     * Synchronous — blocks until all slugs are processed. Sized for small
     * lists (a few dozen slugs at most). Per slug runtime: ~1-2s for the
     * Polymarket lookup + ~3-5s for snapshot backfill.
     *
     * Body: {@code ["btc-updown-5m-1778318700", "btc-updown-15m-1778318700"]}
     */
    @PostMapping("/process-slugs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> processSlugs(
            @RequestBody List<String> slugs) {

        List<Map<String, Object>> results = new ArrayList<>();
        for (String slug : slugs) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("slug", slug);
            try {
                Market market = btcMarketIngestionService.createMarketFromPolymarket(slug);
                if (market == null) {
                    r.put("status", "error");
                    r.put("error", "market not found on polymarket");
                    results.add(r);
                    continue;
                }
                r.put("marketId", market.getMarketId());
                r.put("coin", market.getCoin().name());

                Coin coin = market.getCoin();
                BackfillResult br = snapshotBackupService.backfillSingleMarket(market, coin.name());
                r.put("snapshots", Map.of(
                        "saved", br.saved(),
                        "skipped", br.skipped(),
                        "received", br.received(),
                        "pages", br.pages()
                ));
                r.put("status", "ok");
            } catch (Exception e) {
                log.error("Failed to process slug {}: {}", slug, e.getMessage(), e);
                r.put("status", "error");
                r.put("error", e.getMessage());
            }
            results.add(r);
        }
        return ResponseEntity.ok(ApiResponse.success(results));
    }
}
