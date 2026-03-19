package com.nebula.ingestion.controller;

import com.nebula.common.dto.response.ApiResponse;
import com.nebula.ingestion.service.BtcMarketIngestionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Simple controller for BTC market ingestion - POC
 */
@RestController
@RequestMapping("/api/markets")
@RequiredArgsConstructor
public class BtcMarketController {

    private final BtcMarketIngestionService btcMarketIngestionService;

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
     * Add slugs to track
     * Works for single or multiple slugs
     * 
     * Example: POST /api/markets/track
     * Body: { "slugs": ["btc-updown-5m-1773685200"] }
     */
    @PostMapping("/track")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addSlugs(@RequestBody SlugRequest request) {
        int addedCount = btcMarketIngestionService.addSlugsToTrack(request.getSlugs());
        
        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "message", "Added " + addedCount + " slug(s) to tracking",
            "addedCount", addedCount,
            "activeCount", btcMarketIngestionService.getActiveMarketCount(),
            "activeMarkets", btcMarketIngestionService.getActiveMarketSlugs()
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

    @Data
    public static class SlugRequest {
        private List<String> slugs;
    }
}
