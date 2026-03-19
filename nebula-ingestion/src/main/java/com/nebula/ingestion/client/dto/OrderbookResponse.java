package com.nebula.ingestion.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderbookResponse {

    @JsonProperty("market")
    private String market;

    @JsonProperty("asset_id")
    private String assetId;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("bids")
    private List<PriceLevel> bids;

    @JsonProperty("asks")
    private List<PriceLevel> asks;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PriceLevel {
        @JsonProperty("price")
        private String price;

        @JsonProperty("size")
        private String size;

        public Map<String, Object> toMap() {
            return Map.of(
                "price", Double.parseDouble(price),
                "size", Double.parseDouble(size)
            );
        }
    }

    /**
     * Convert bids to list of maps for JSON storage
     */
    public List<Map<String, Object>> getBidsAsMaps() {
        if (bids == null) return List.of();
        return bids.stream()
                .map(PriceLevel::toMap)
                .collect(Collectors.toList());
    }

    /**
     * Convert asks to list of maps for JSON storage
     */
    public List<Map<String, Object>> getAsksAsMaps() {
        if (asks == null) return List.of();
        return asks.stream()
                .map(PriceLevel::toMap)
                .collect(Collectors.toList());
    }

    /**
     * Build orderbook map for storage (all levels)
     */
    public Map<String, Object> toOrderbookMap() {
        return Map.of(
            "bids", getBidsAsMaps(),
            "asks", getAsksAsMaps()
        );
    }

    /**
     * Build orderbook map for storage (limited to top N levels)
     */
    public Map<String, Object> toOrderbookMap(int limit) {
        return Map.of(
            "bids", getBidsAsMaps().stream().limit(limit).collect(Collectors.toList()),
            "asks", getAsksAsMaps().stream().limit(limit).collect(Collectors.toList())
        );
    }
}
