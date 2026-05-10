package com.nebula.ingestion.client.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link PolyBacktestSnapshot}'s {@code coinPrice} field
 * correctly deserializes from any of the four reference-price field names
 * polybacktest uses across coins / endpoint variants.
 */
class PolyBacktestSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void deserializes_btc_price() throws Exception {
        String json = """
            {
              "id": 1169976759,
              "time": "2026-04-24T02:05:00.039600Z",
              "market_id": "2056333",
              "btc_price": 78460.48,
              "price_up": 0.45,
              "price_down": 0.56
            }
            """;
        PolyBacktestSnapshot s = mapper.readValue(json, PolyBacktestSnapshot.class);
        assertEquals(new BigDecimal("78460.48"), s.getCoinPrice(), "btc_price should map to coinPrice");
        assertEquals(new BigDecimal("0.45"), s.getPriceUp());
        assertEquals("2056333", s.getMarketId());
    }

    @Test
    void deserializes_eth_price() throws Exception {
        String json = """
            {
              "id": 147967928,
              "time": "2026-05-10T18:20:00.011791Z",
              "market_id": "2216509",
              "eth_price": 2361.22,
              "price_up": 0.47,
              "price_down": 0.55
            }
            """;
        PolyBacktestSnapshot s = mapper.readValue(json, PolyBacktestSnapshot.class);
        assertEquals(new BigDecimal("2361.22"), s.getCoinPrice(), "eth_price should map to coinPrice");
    }

    @Test
    void deserializes_sol_price() throws Exception {
        String json = """
            {
              "id": 1,
              "time": "2026-05-10T18:20:00Z",
              "market_id": "1234",
              "sol_price": 93.42,
              "price_up": 0.50,
              "price_down": 0.51
            }
            """;
        PolyBacktestSnapshot s = mapper.readValue(json, PolyBacktestSnapshot.class);
        assertEquals(new BigDecimal("93.42"), s.getCoinPrice(), "sol_price should map to coinPrice");
    }

    @Test
    void coinPrice_is_null_when_no_price_field_present() throws Exception {
        String json = """
            {
              "id": 1,
              "time": "2026-05-10T18:20:00Z",
              "market_id": "1234",
              "price_up": 0.50,
              "price_down": 0.51
            }
            """;
        PolyBacktestSnapshot s = mapper.readValue(json, PolyBacktestSnapshot.class);
        assertNull(s.getCoinPrice(), "missing price field should leave coinPrice null");
    }
}
