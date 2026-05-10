package com.nebula.ingestion.scheduler;

import com.nebula.ingestion.service.BtcMarketIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scheduler for market snapshots
 * Snapshot interval is configurable via application.yml
 */
@Component
@ConditionalOnProperty(name = "nebula.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class BtcMarketScheduler {

    private final BtcMarketIngestionService btcMarketIngestionService;
    private final ExecutorService snapshotExecutor;

    @Value("${nebula.snapshot.log-each-tick:false}")
    private boolean logEachTick;

    @Value("${nebula.retention.days:31}")
    private int retentionDays;

    @Value("${nebula.retention.vacuum:true}")
    private boolean vacuumEnabled;

    @Value("${nebula.retention.vacuum-full:false}")
    private boolean vacuumFull;

    public BtcMarketScheduler(BtcMarketIngestionService btcMarketIngestionService) {
        this.btcMarketIngestionService = btcMarketIngestionService;
        this.snapshotExecutor = Executors.newFixedThreadPool(4);
    }

    /**
     * Takes snapshots for short-duration markets (5m, 15m, 1hr)
     * Configure via: nebula.snapshot.interval-ms
     */
    @Scheduled(fixedRateString = "${nebula.snapshot.interval-ms:500}")
    public void snapshotShortMarkets() {
        if (logEachTick) {
            log.info("--- Short market snapshot tick at {} ---", Instant.now());
        }

        snapshotExecutor.submit(() -> {
            try {
                btcMarketIngestionService.snapshotShortMarkets();
            } catch (Exception e) {
                log.error("Short market snapshot failed", e);
            }
        });
    }

    /**
     * Takes snapshots for long-duration markets (4h, 24h) at 1 per second
     * Configure via: nebula.snapshot.long-interval-ms
     */
    @Scheduled(fixedRateString = "${nebula.snapshot.long-interval-ms:1000}")
    public void snapshotLongMarkets() {
        if (logEachTick) {
            log.info("--- Long market snapshot tick at {} ---", Instant.now());
        }

        snapshotExecutor.submit(() -> {
            try {
                btcMarketIngestionService.snapshotLongMarkets();
            } catch (Exception e) {
                log.error("Long market snapshot failed", e);
            }
        });
    }

    /**
     * Deactivate expired markets
     * Configure via: nebula.snapshot.deactivate-interval-ms (default 150000ms = 2.5m)
     */
    @Scheduled(fixedRateString = "${nebula.snapshot.deactivate-interval-ms:150000}")
    public void deactivateExpiredMarkets() {
        try {
            btcMarketIngestionService.deactivateExpiredMarkets();
        } catch (Exception e) {
            log.error("Failed to deactivate expired markets", e);
        }
    }

    /**
     * Delete markets (and their snapshots) older than the configured retention
     * window. Runs once a day at 10:00 AM India Standard Time.
     * Configure via:
     *   nebula.retention.days (default 31)
     *   nebula.retention.cleanup-cron (default "0 0 10 * * *")
     *   nebula.retention.cleanup-zone (default "Asia/Kolkata")
     */
    @Scheduled(
            cron = "${nebula.retention.cleanup-cron:0 0 10 * * *}",
            zone = "${nebula.retention.cleanup-zone:Asia/Kolkata}"
    )
    public void cleanupOldMarkets() {
        log.info("--- Old market cleanup tick at {} (retention={} days) ---", Instant.now(), retentionDays);
        try {
            btcMarketIngestionService.deleteMarketsOlderThan(retentionDays);
        } catch (Exception e) {
            log.error("Failed to delete old markets", e);
            return;
        }
        if (vacuumEnabled) {
            try {
                btcMarketIngestionService.vacuumMarketTables(vacuumFull);
            } catch (Exception e) {
                log.error("VACUUM after cleanup failed", e);
            }
        }
    }
}
