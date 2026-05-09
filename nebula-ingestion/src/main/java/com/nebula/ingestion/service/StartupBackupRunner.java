package com.nebula.ingestion.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fires the historical snapshot backup automatically once on application
 * startup, but only when {@code polybacktest.backup.run-on-startup=true}.
 *
 * Default is {@code false} so a normal redeploy is a no-op. To run the
 * backfill, set {@code BACKUP_RUN_ON_STARTUP=true} in the deploy env, restart
 * the container, then flip it back off (or just leave it — dedup makes
 * re-runs cheap, but they still burn polybacktest quota).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartupBackupRunner {

    private final SnapshotBackupService backupService;

    @Value("${polybacktest.backup.run-on-startup:false}")
    private boolean runOnStartup;

    @Value("${polybacktest.backup.coin-prefix:btc}")
    private String coinPrefix;

    @Value("${polybacktest.backup.interval:5m}")
    private String interval;

    @Value("${polybacktest.backup.coin:BTC}")
    private String coin;

    @Value("${polybacktest.backup.start-epoch:1778320800}")
    private long startEpoch;

    @Value("${polybacktest.backup.end-epoch:1775645914}")
    private long endEpoch;

    @Value("${polybacktest.backup.step-seconds:300}")
    private int stepSeconds;

    @Value("${polybacktest.backup.max-iterations:10000}")
    private int maxIterations;

    @Value("${polybacktest.backup.max-consecutive-missing:500}")
    private int maxConsecutiveMissing;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!runOnStartup) {
            log.info("Startup backup disabled (polybacktest.backup.run-on-startup=false)");
            return;
        }
        BackupRequest req = BackupRequest.builder()
                .coinPrefix(coinPrefix)
                .interval(interval)
                .coin(coin)
                .startEpoch(startEpoch)
                .endEpoch(endEpoch > 0 ? endEpoch : null)
                .stepSeconds(stepSeconds)
                .maxIterations(maxIterations)
                .maxConsecutiveMissing(maxConsecutiveMissing)
                .build();
        log.info("Auto-triggering snapshot backup on startup: {}", req);
        try {
            backupService.start(req);
        } catch (IllegalStateException e) {
            log.warn("Backup already running; skipping startup trigger");
        } catch (Exception e) {
            log.error("Failed to trigger startup backup", e);
        }
    }
}
