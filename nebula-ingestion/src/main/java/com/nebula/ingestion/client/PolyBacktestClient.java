package com.nebula.ingestion.client;

import com.nebula.ingestion.client.dto.PolyBacktestSnapshotPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;

/**
 * Client for the polybacktest.com snapshot backup API.
 *
 * Endpoint:
 *   GET /v2/markets/{marketId}/snapshots?coin=BTC&include_orderbook=true&limit=1000
 *
 * The vendor enforces a per-minute request budget. We respect this with a
 * strict inter-request spacing throttle: every call blocks until at least
 * (60_000 / rpm) ms have elapsed since the previous call, so the maximum
 * sustained rate is exactly the configured RPM — no bursts.
 *
 * Pagination is offset-based: each response includes {@code total},
 * {@code limit}, and {@code offset}. The caller increments offset by the
 * page size on each call until offset >= total.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PolyBacktestClient {

    private final WebClient webClient;

    private static final String API_KEY_HEADER = "X-API-Key";

    @Value("${polybacktest.api.base-url:https://api.polybacktest.com}")
    private String baseUrl;

    @Value("${polybacktest.api.api-key:}")
    private String apiKey;

    @Value("${polybacktest.api.rate-limit-rpm:300}")
    private int rateLimitRpm;

    @Value("${polybacktest.api.page-size:1000}")
    private int pageSize;

    @Value("${polybacktest.api.retry-attempts:3}")
    private int retryAttempts;

    @Value("${polybacktest.api.retry-delay-ms:1000}")
    private long retryDelayMs;

    private final Object throttleLock = new Object();
    private long lastRequestNanos = 0L;

    /**
     * Block until the rate limiter allows the next call. Strict spacing of
     * (60_000 / rpm) ms between successive calls — i.e. at 300 RPM, 200 ms
     * between calls. Synchronized so concurrent callers serialize through
     * the same gate.
     */
    private void awaitRateLimit() {
        long minSpacingNanos = 60_000_000_000L / Math.max(1, rateLimitRpm);
        synchronized (throttleLock) {
            long now = System.nanoTime();
            long earliest = lastRequestNanos + minSpacingNanos;
            if (now < earliest) {
                long waitNanos = earliest - now;
                long waitMs = waitNanos / 1_000_000L;
                int waitNs = (int) (waitNanos % 1_000_000L);
                try {
                    Thread.sleep(waitMs, waitNs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestNanos = System.nanoTime();
        }
    }

    /**
     * Fetch a single page of snapshots for a market.
     *
     * @param marketId external (polybacktest) market id
     * @param coin     query value, e.g. "BTC"
     * @param offset   zero-based offset into the snapshot stream
     * @return the parsed envelope; {@link PolyBacktestSnapshotPage#getSnapshots()}
     *         is empty when no more snapshots are available
     */
    public PolyBacktestSnapshotPage fetchSnapshotsPage(String marketId, String coin, int offset) {
        awaitRateLimit();

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment("v2", "markets", marketId, "snapshots")
                .queryParam("coin", coin)
                .queryParam("include_orderbook", true)
                .queryParam("limit", pageSize)
                .queryParam("offset", offset)
                .build(false)
                .toUri();

        try {
            WebClient.RequestHeadersSpec<?> request = webClient.get().uri(uri);
            if (apiKey != null && !apiKey.isBlank()) {
                request = request.header(API_KEY_HEADER, apiKey);
            }
            PolyBacktestSnapshotPage page = request
                    .retrieve()
                    .bodyToMono(PolyBacktestSnapshotPage.class)
                    .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryDelayMs))
                            .filter(this::isRetryable))
                    .block(Duration.ofSeconds(60));
            return page != null ? page : new PolyBacktestSnapshotPage();
        } catch (WebClientResponseException.NotFound nf) {
            log.debug("polybacktest 404 for market {} (offset={})", marketId, offset);
            return new PolyBacktestSnapshotPage();
        } catch (Exception e) {
            log.error("polybacktest fetch failed for market {} offset={}: {}",
                    marketId, offset, e.getMessage());
            throw e;
        }
    }

    /**
     * Page size used for each call (so callers can detect the last page when
     * the server returns fewer rows than requested).
     */
    public int getPageSize() {
        return pageSize;
    }

    private boolean isRetryable(Throwable ex) {
        if (ex instanceof WebClientResponseException w) {
            int code = w.getStatusCode().value();
            // 429 = rate limit (retry with backoff), 5xx = server-side issue
            return code == 429 || code >= 500;
        }
        // Network-level errors are retryable
        return true;
    }
}
