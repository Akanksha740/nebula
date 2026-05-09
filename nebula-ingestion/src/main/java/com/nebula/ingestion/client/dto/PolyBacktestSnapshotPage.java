package com.nebula.ingestion.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * Envelope returned by:
 *   GET /v2/markets/{marketId}/snapshots?coin=BTC&include_orderbook=true&limit=N&offset=N
 *
 * Shape (additional fields like {@code market} are ignored):
 * <pre>{@code
 * {
 *   "snapshots": [ ...PolyBacktestSnapshot ],
 *   "total":  2370,
 *   "limit":  1000,
 *   "offset": 0
 * }
 * }</pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolyBacktestSnapshotPage {

    private List<PolyBacktestSnapshot> snapshots;
    private Integer total;
    private Integer limit;
    private Integer offset;

    public List<PolyBacktestSnapshot> snapshotsOrEmpty() {
        return snapshots != null ? snapshots : Collections.emptyList();
    }
}
