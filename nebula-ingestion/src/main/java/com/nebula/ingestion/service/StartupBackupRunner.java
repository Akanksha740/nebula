package com.nebula.ingestion.service;

import com.nebula.ingestion.repository.BackupProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Fires historical snapshot backups automatically on application startup,
 * sequentially across one or more intervals (e.g. 5m, then 15m).
 *
 * Configured via {@code polybacktest.backup.intervals} — a comma-separated
 * list. The runner orchestrates one backup at a time on a daemon thread,
 * waiting for each to finish before starting the next. Each interval has its
 * own row in {@code backup_progress} (keyed by {@code coinPrefix-updown-iv}),
 * so resume state is per-interval.
 *
 * {@code BACKUP_FORCE_FRESH=true} wipes all rows in
 * {@code backup_progress} before the first run starts, so each restart
 * re-walks every configured interval from {@code startEpoch}. The count
 * heuristic in {@link SnapshotBackupService} skips already-complete markets
 * sub-millisecond, so the cost after the first full run is negligible.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartupBackupRunner {

    private final SnapshotBackupService backupService;
    private final BackupProgressRepository progressRepository;

    @Value("${polybacktest.backup.run-on-startup:false}")
    private boolean runOnStartup;

    @Value("${polybacktest.backup.force-fresh:false}")
    private boolean forceFresh;

    @Value("${polybacktest.backup.coin-prefix:btc}")
    private String coinPrefix;

    @Value("${polybacktest.backup.coin:BTC}")
    private String coin;

    /** Comma-separated list of intervals to walk in order, e.g. "5m,15m". */
    @Value("${polybacktest.backup.intervals:5m,15m}")
    private String intervalsCsv;

    @Value("${polybacktest.backup.start-epoch:1778320800}")
    private long startEpoch;

    @Value("${polybacktest.backup.end-epoch:1775645914}")
    private long endEpoch;

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
        if (forceFresh) {
            try {
                long deleted = progressRepository.count();
                progressRepository.deleteAll();
                log.warn("BACKUP_FORCE_FRESH=true — wiped {} backup_progress row(s) for clean restart", deleted);
            } catch (Exception e) {
                log.error("Failed to wipe backup_progress; continuing anyway: {}", e.getMessage());
            }
        }

        List<String> intervals = parseIntervals();
        if (intervals.isEmpty()) {
            log.warn("No intervals configured (polybacktest.backup.intervals is empty); skipping startup backup");
            return;
        }

        Thread orchestrator = new Thread(() -> orchestrate(intervals), "backup-orchestrator");
        orchestrator.setDaemon(true);
        orchestrator.start();
    }

    private void orchestrate(List<String> intervals) {
        log.info("Backup orchestration starting for intervals={}", intervals);
        for (String iv : intervals) {
            BackupRequest req = BackupRequest.builder()
                    .coinPrefix(coinPrefix)
                    .interval(iv)
                    .coin(coin)
                    .startEpoch(startEpoch)
                    .endEpoch(endEpoch > 0 ? endEpoch : null)
                    .stepSeconds(stepSecondsFor(iv))
                    .maxIterations(maxIterations)
                    .maxConsecutiveMissing(maxConsecutiveMissing)
                    .build();
            log.info("[orchestrator] starting interval={} step={}s: {}",
                    iv, req.getStepSeconds(), req);
            try {
                backupService.start(req);
                while (backupService.isRunning()) {
                    Thread.sleep(15_000);
                }
                log.info("[orchestrator] interval={} complete; status={}", iv, backupService.getStatus());
            } catch (IllegalStateException e) {
                log.warn("[orchestrator] interval={} skipped — another backup already running: {}",
                        iv, e.getMessage());
            } catch (InterruptedException e) {
                log.warn("[orchestrator] interrupted during interval={}; aborting orchestration", iv);
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("[orchestrator] interval={} failed; continuing with next", iv, e);
            }
        }
        log.info("Backup orchestration finished for intervals={}", intervals);
    }

    private List<String> parseIntervals() {
        if (intervalsCsv == null || intervalsCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(intervalsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Step in seconds for a given interval token. Falls back to 300 (5m) for
     * unrecognised values so a typo doesn't crash the run.
     */
    private int stepSecondsFor(String interval) {
        return switch (interval) {
            case "5m" -> 300;
            case "15m" -> 900;
            case "1h" -> 3600;
            case "4h" -> 14_400;
            case "24h" -> 86_400;
            default -> {
                log.warn("Unknown interval '{}', defaulting step to 300s", interval);
                yield 300;
            }
        };
    }
}
