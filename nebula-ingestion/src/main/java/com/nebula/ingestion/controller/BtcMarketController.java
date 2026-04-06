package com.nebula.ingestion.controller;

import com.nebula.common.dto.response.ApiResponse;
import com.nebula.ingestion.service.BtcMarketIngestionService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

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
}
