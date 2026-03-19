package com.nebula.ingestion.scheduler;

import com.nebula.ingestion.service.BtcMarketIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
@Slf4j
public class BtcMarketScheduler {

    private final BtcMarketIngestionService btcMarketIngestionService;
    private final ExecutorService snapshotExecutor;

    @Value("${nebula.snapshot.log-each-tick:false}")
    private boolean logEachTick;

    public BtcMarketScheduler(BtcMarketIngestionService btcMarketIngestionService) {
        this.btcMarketIngestionService = btcMarketIngestionService;
        this.snapshotExecutor = Executors.newFixedThreadPool(4);
    }

    /**
     * Takes snapshots at configurable interval
     * Default: every 500ms (2 per second)
     * Configure via: nebula.snapshot.interval-ms
     */
    @Scheduled(fixedRateString = "${nebula.snapshot.interval-ms:500}")
    public void snapshotActiveMarkets() {
        if (logEachTick) {
            log.info("--- Snapshot tick at {} ---", Instant.now());
        }
        
        snapshotExecutor.submit(() -> {
            try {
                btcMarketIngestionService.snapshotActiveMarkets();
            } catch (Exception e) {
                log.error("Snapshot failed", e);
            }
        });
    }

    /**
     * Deactivate expired markets every 15 minutes
     */
    @Scheduled(fixedRate = 900000)
    public void deactivateExpiredMarkets() {
        try {
            btcMarketIngestionService.deactivateExpiredMarkets();
        } catch (Exception e) {
            log.error("Failed to deactivate expired markets", e);
        }
    }
}
