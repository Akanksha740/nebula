package com.nebula.ingestion.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolymarketMarket {

    @JsonProperty("id")
    private String id;

    @JsonProperty("question")
    private String question;

    @JsonProperty("description")
    private String description;

    @JsonProperty("category")
    private String category;

    @JsonProperty("slug")
    private String slug;

    @JsonProperty("conditionId")
    private String conditionId;

    @JsonProperty("active")
    private Boolean active;

    @JsonProperty("closed")
    private Boolean closed;

    @JsonProperty("archived")
    private Boolean archived;

    @JsonProperty("endDate")
    private Instant endDate;

    @JsonProperty("startDate")
    private Instant startDate;

    @JsonProperty("eventStartTime")
    private Instant eventStartTime;

    @JsonProperty("createdAt")
    private Instant createdAt;

    @JsonProperty("updatedAt")
    private Instant updatedAt;

    @JsonProperty("closedTime")
    private String closedTime;

    @JsonProperty("volume")
    private BigDecimal volume;

    @JsonProperty("volume24hr")
    private BigDecimal volume24h;

    @JsonProperty("liquidity")
    private BigDecimal liquidity;

    @JsonProperty("outcomes")
    @JsonDeserialize(using = StringOrListDeserializer.class)
    private List<String> outcomes;

    @JsonProperty("outcomePrices")
    @JsonDeserialize(using = StringOrDecimalListDeserializer.class)
    private List<BigDecimal> outcomePrices;

    @JsonProperty("tokens")
    private List<Token> tokens;

    @JsonProperty("clobTokenIds")
    private String clobTokenIds;

    @JsonProperty("resolutionSource")
    private String resolutionSource;

    @JsonProperty("marketType")
    private String marketType;

    @JsonProperty("tags")
    private List<String> tags;

    @JsonProperty("bestBid")
    private BigDecimal bestBid;

    @JsonProperty("bestAsk")
    private BigDecimal bestAsk;

    @JsonProperty("lastTradePrice")
    private BigDecimal lastTradePrice;

    @JsonProperty("events")
    private List<Event> events;

    /**
     * Custom deserializer to handle outcomes as either JSON array or JSON string containing array
     */
    public static class StringOrListDeserializer extends JsonDeserializer<List<String>> {
        private static final ObjectMapper mapper = new ObjectMapper();
        
        @Override
        public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken().isScalarValue()) {
                // It's a string like "[\"Up\", \"Down\"]"
                String value = p.getValueAsString();
                if (value != null && value.startsWith("[")) {
                    return mapper.readValue(value, new TypeReference<List<String>>() {});
                }
                return List.of(value);
            } else {
                // It's already an array
                return mapper.readValue(p, new TypeReference<List<String>>() {});
            }
        }
    }

    /**
     * Custom deserializer to handle outcomePrices as either JSON array or JSON string containing array
     */
    public static class StringOrDecimalListDeserializer extends JsonDeserializer<List<BigDecimal>> {
        private static final ObjectMapper mapper = new ObjectMapper();
        
        @Override
        public List<BigDecimal> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken().isScalarValue()) {
                // It's a string like "[\"0.5\", \"0.5\"]"
                String value = p.getValueAsString();
                if (value != null && value.startsWith("[")) {
                    List<String> stringList = mapper.readValue(value, new TypeReference<List<String>>() {});
                    List<BigDecimal> result = new ArrayList<>();
                    for (String s : stringList) {
                        result.add(new BigDecimal(s));
                    }
                    return result;
                }
                return List.of(new BigDecimal(value));
            } else {
                // It's already an array - could be strings or numbers
                List<Object> rawList = mapper.readValue(p, new TypeReference<List<Object>>() {});
                List<BigDecimal> result = new ArrayList<>();
                for (Object obj : rawList) {
                    if (obj instanceof String) {
                        result.add(new BigDecimal((String) obj));
                    } else if (obj instanceof Number) {
                        result.add(new BigDecimal(obj.toString()));
                    }
                }
                return result;
            }
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Token {
        @JsonProperty("token_id")
        private String tokenId;

        @JsonProperty("outcome")
        private String outcome;

        @JsonProperty("price")
        private BigDecimal price;

        @JsonProperty("winner")
        private Boolean winner;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Event {
        @JsonProperty("id")
        private String id;

        @JsonProperty("slug")
        private String slug;

        @JsonProperty("title")
        private String title;

        @JsonProperty("startTime")
        private Instant startTime;

        @JsonProperty("endDate")
        private Instant endDate;

        @JsonProperty("closed")
        private Boolean closed;

        @JsonProperty("automaticallyResolved")
        private Boolean automaticallyResolved;

        @JsonProperty("eventMetadata")
        private Map<String, Object> eventMetadata;
    }

    public String getStatus() {
        if (Boolean.TRUE.equals(archived)) {
            return "archived";
        }
        if (Boolean.TRUE.equals(closed)) {
            return "resolved";
        }
        if (Boolean.TRUE.equals(active)) {
            return "active";
        }
        return "inactive";
    }

    public Map<String, BigDecimal> getOutcomePricesMap() {
        if (outcomes == null || outcomePrices == null) {
            return Map.of();
        }
        
        var result = new java.util.HashMap<String, BigDecimal>();
        for (int i = 0; i < Math.min(outcomes.size(), outcomePrices.size()); i++) {
            BigDecimal price = outcomePrices.get(i);
            result.put(outcomes.get(i), price);
        }
        return result;
    }

    public BigDecimal getPriceUp() {
        Map<String, BigDecimal> prices = getOutcomePricesMap();
        return prices.getOrDefault("Up", BigDecimal.ZERO);
    }

    public BigDecimal getPriceDown() {
        Map<String, BigDecimal> prices = getOutcomePricesMap();
        return prices.getOrDefault("Down", BigDecimal.ZERO);
    }

    public String getClobTokenUp() {
        if (tokens != null) {
            for (Token t : tokens) {
                if ("Up".equalsIgnoreCase(t.getOutcome())) {
                    return t.getTokenId();
                }
            }
        }
        // Parse from clobTokenIds JSON array string like ["token1", "token2"]
        if (clobTokenIds != null) {
            List<String> tokenList = parseClobTokenIds();
            if (!tokenList.isEmpty()) {
                return tokenList.get(0);
            }
        }
        return null;
    }

    public String getClobTokenDown() {
        if (tokens != null) {
            for (Token t : tokens) {
                if ("Down".equalsIgnoreCase(t.getOutcome())) {
                    return t.getTokenId();
                }
            }
        }
        // Parse from clobTokenIds JSON array string like ["token1", "token2"]
        if (clobTokenIds != null) {
            List<String> tokenList = parseClobTokenIds();
            if (tokenList.size() > 1) {
                return tokenList.get(1);
            }
        }
        return null;
    }

    private List<String> parseClobTokenIds() {
        if (clobTokenIds == null) return List.of();
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<String> tokens = mapper.readValue(clobTokenIds, new TypeReference<List<String>>() {});
            // Clean each token ID (remove any remaining quotes or brackets)
            return tokens.stream()
                    .map(this::cleanTokenId)
                    .filter(t -> !t.isEmpty())
                    .toList();
        } catch (Exception e) {
            // Try manual parsing if JSON parsing fails
            String cleaned = clobTokenIds.replaceAll("[\\[\\]\"]", "");
            if (!cleaned.isEmpty()) {
                return List.of(cleaned.split(",")).stream()
                        .map(String::trim)
                        .filter(t -> !t.isEmpty())
                        .toList();
            }
            return List.of();
        }
    }

    private String cleanTokenId(String tokenId) {
        if (tokenId == null) return "";
        return tokenId.replaceAll("[\\[\\]\"]", "").trim();
    }

    public String getWinner() {
        if (tokens != null) {
            for (Token t : tokens) {
                if (Boolean.TRUE.equals(t.getWinner())) {
                    return t.getOutcome();
                }
            }
        }
        return null;
    }

    public BigDecimal getCoinPriceStart() {
        if (events != null && !events.isEmpty()) {
            Event e = events.get(0);
            if (e.getEventMetadata() != null) {
                Object price = e.getEventMetadata().get("priceToBeat");
                if (price != null) {
                    return new BigDecimal(price.toString());
                }
            }
        }
        return null;
    }

    public String getEventId() {
        if (events != null && !events.isEmpty()) {
            return events.get(0).getId();
        }
        return null;
    }

    public String getMarketTypeResolved() {
        if (slug != null) {
            String lowerSlug = slug.toLowerCase();
            
            // Short format: btc-updown-5m-1773755100
            if (lowerSlug.contains("-5m-")) return "5m";
            if (lowerSlug.contains("-15m-")) return "15m";
            if (lowerSlug.contains("-1h-")) return "1h";
            if (lowerSlug.contains("-4h-")) return "4h";
            if (lowerSlug.contains("-24h-")) return "24h";
            
            // Long format: bitcoin-up-or-down-*
            if (lowerSlug.startsWith("bitcoin-") || lowerSlug.startsWith("ethereum-") || lowerSlug.startsWith("solana-")) {
                // Check for hourly pattern: bitcoin-up-or-down-march-17-2026-9am-et (contains time like 9am, 10pm)
                if (lowerSlug.matches(".*-\\d{1,2}(am|pm)-.*")) {
                    return "1h";
                }
                // Daily pattern: bitcoin-up-or-down-on-march-17-2026 or bitcoin-up-or-down-march-17-2026 (no time)
                return "24h";
            }
        }
        return marketType;
    }
}
