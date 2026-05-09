package com.nebula.ingestion.service;

import com.nebula.common.entity.BackupProgress;
import com.nebula.common.entity.Market;
import com.nebula.common.entity.MarketSnapshot;
import com.nebula.ingestion.client.PolyBacktestClient;
import com.nebula.ingestion.client.dto.PolyBacktestSnapshot;
import com.nebula.ingestion.client.dto.PolyBacktestSnapshotPage;
import com.nebula.ingestion.repository.BackupProgressRepository;
import com.nebula.ingestion.repository.MarketRepository;
import com.nebula.ingestion.repository.MarketSnapshotRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot historical backfill job that pulls snapshots from the
 * polybacktest.com API for markets we already have in our markets table.
 *
 * Walk:
 *   for epoch = startEpoch; epoch >= endEpoch; epoch -= stepSeconds
 *     slug   = "{coinPrefix}-updown-{interval}-{epoch}"     e.g. btc-updown-5m-1778320800
 *     market = markets.findBySlug(slug)
 *     if market is null → counts as a "missing" iteration
 *     else paginate snapshots for market.marketId from polybacktest, persist new ones
 *
 * Single-threaded by design: the polybacktest rate limit (10 RPS) is the
 * bottleneck, and serial execution makes progress easy to reason about.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SnapshotBackupService {

    private static final int DEFAULT_STEP_SECONDS = 300; // 5 minutes
    private static final int DB_BATCH_SIZE = 200;

    private final PolyBacktestClient client;
    private final MarketRepository marketRepository;
    private final MarketSnapshotRepository snapshotRepository;
    private final BackupProgressRepository progressRepository;
    private final SnapshotBackupPersistence persistence;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "snapshot-backup");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    @Getter
    private volatile BackupStatus status = BackupStatus.idle();

    /**
     * Start an async backup. Returns immediately. Throws if a job is already
     * running — backups are mutually exclusive to keep the DB write rate
     * predictable.
     */
    public synchronized BackupStatus start(BackupRequest req) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A backup is already running");
        }
        cancelRequested.set(false);
        BackupRequest validated = req.normalized();
        BackupStatus initial = BackupStatus.starting(validated);
        this.status = initial;
        executor.submit(() -> {
            try {
                run(validated);
            } catch (Exception e) {
                log.error("Backup failed", e);
                this.status = this.status.withError(e.getMessage());
            } finally {
                running.set(false);
            }
        });
        return initial;
    }

    /** Request cancellation; the running job will stop after the current slug. */
    public void requestCancel() {
        if (running.get()) {
            cancelRequested.set(true);
            log.info("Backup cancellation requested");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    // ────────────────────────────────────────────────────────────────────────

    private void run(BackupRequest req) {
        log.info("Backup starting: {}", req);
        long startMs = System.currentTimeMillis();

        String progressKey = String.format("%s-updown-%s", req.getCoinPrefix(), req.getInterval());
        long epoch = resolveStartingEpoch(req, progressKey);
        if (req.getEndEpoch() != null && epoch < req.getEndEpoch()) {
            log.info("Backup '{}' is already complete (resumed epoch {} < endEpoch {}). Nothing to do.",
                    progressKey, epoch, req.getEndEpoch());
            this.status = BackupStatus.builder()
                    .request(req)
                    .running(false)
                    .currentEpoch(epoch)
                    .iterations(0)
                    .startedAtMs(startMs)
                    .finishedAtMs(System.currentTimeMillis())
                    .build();
            return;
        }

        int iterationCount = 0;
        int marketsHit = 0;
        int marketsMissing = 0;
        int consecutiveMissing = 0;
        long totalSnapshotsSaved = 0;
        long totalSnapshotsSkipped = 0;

        while (true) {
            if (cancelRequested.get()) {
                log.info("Backup cancelled at iteration {}", iterationCount);
                break;
            }
            if (iterationCount >= req.getMaxIterations()) {
                log.info("Backup hit maxIterations={}", req.getMaxIterations());
                break;
            }
            if (req.getEndEpoch() != null && epoch < req.getEndEpoch()) {
                log.info("Backup reached endEpoch={}", req.getEndEpoch());
                break;
            }
            if (consecutiveMissing >= req.getMaxConsecutiveMissing()) {
                log.info("Backup stopping: {} consecutive missing markets", consecutiveMissing);
                break;
            }

            String slug = String.format("%s-updown-%s-%d", req.getCoinPrefix(), req.getInterval(), epoch);
            Market market = marketRepository.findBySlug(slug).orElse(null);

            if (market == null) {
                marketsMissing++;
                consecutiveMissing++;
                log.debug("[{}] no market in DB", slug);
            } else if (market.getMarketId() == null || market.getMarketId().isBlank()) {
                marketsMissing++;
                consecutiveMissing++;
                log.warn("[{}] market in DB has no external marketId, skipping", slug);
            } else {
                consecutiveMissing = 0;
                marketsHit++;
                // Cheap pre-check: a partial save can only have left an exact
                // multiple of pageSize in the DB (whole-page transactions
                // commit atomically). If the existing count is non-zero and
                // not a multiple of pageSize, the market is already whole and
                // we can skip the polybacktest call entirely.
                long existingCount = persistence.countSnapshotsForMarket(market.getId());
                int pageSize = client.getPageSize();
                boolean suspectIncomplete = existingCount == 0L || (existingCount % pageSize) == 0L;
                if (!suspectIncomplete) {
                    log.info("[{}] skipping API call — existing snapshots={} (not a page-multiple, treating as complete)",
                            slug, existingCount);
                    totalSnapshotsSkipped += existingCount;
                } else {
                    try {
                        log.info("[{}] backfilling marketId={} existing={} (iter {}/{})",
                                slug, market.getMarketId(), existingCount,
                                iterationCount + 1, req.getMaxIterations());
                        BackfillResult r = backfillMarket(slug, market, req.getCoin());
                        totalSnapshotsSaved += r.saved;
                        totalSnapshotsSkipped += r.skipped;
                        long covered = r.saved + r.skipped;
                        if (r.expectedTotal != null && covered < r.expectedTotal) {
                            log.warn("[{}] INCOMPLETE: covered={} expected={} (saved={} skipped={} pages={}) — re-run to top off",
                                    slug, covered, r.expectedTotal, r.saved, r.skipped, r.pages);
                        } else {
                            log.info("[{}] done: saved={} skipped={} pages={} expected={}",
                                    slug, r.saved, r.skipped, r.pages, r.expectedTotal);
                        }
                    } catch (Exception e) {
                        log.error("[{}] backfill failed: {}", slug, e.getMessage(), e);
                        // continue with next slug — don't let one bad market kill the run
                    }
                }
            }

            iterationCount++;

            // Persist progress after each iteration so a restart resumes from
            // the next epoch below. Done before decrementing `epoch` so the
            // recorded value is the slug we just finished.
            persistence.upsertProgress(progressKey, req, epoch, slug,
                    iterationCount, totalSnapshotsSaved, startMs);

            epoch -= req.getStepSeconds();

            // Update status snapshot for the controller to read
            this.status = BackupStatus.builder()
                    .request(req)
                    .running(true)
                    .currentEpoch(epoch)
                    .currentSlug(slug)
                    .iterations(iterationCount)
                    .marketsHit(marketsHit)
                    .marketsMissing(marketsMissing)
                    .snapshotsSaved(totalSnapshotsSaved)
                    .snapshotsSkipped(totalSnapshotsSkipped)
                    .startedAtMs(startMs)
                    .build();
        }

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("Backup done in {}ms: iterations={} hit={} missing={} saved={} skipped={}",
                elapsed, iterationCount, marketsHit, marketsMissing,
                totalSnapshotsSaved, totalSnapshotsSkipped);

        this.status = BackupStatus.builder()
                .request(req)
                .running(false)
                .iterations(iterationCount)
                .marketsHit(marketsHit)
                .marketsMissing(marketsMissing)
                .snapshotsSaved(totalSnapshotsSaved)
                .snapshotsSkipped(totalSnapshotsSkipped)
                .startedAtMs(startMs)
                .finishedAtMs(System.currentTimeMillis())
                .currentEpoch(epoch)
                .build();
    }

    /**
     * Decide which epoch to start from. If we have persisted progress for this
     * run key whose {@code lastEpoch} sits inside the requested range, resume
     * one step below it. Otherwise fall back to the request's startEpoch.
     */
    private long resolveStartingEpoch(BackupRequest req, String progressKey) {
        Optional<BackupProgress> prev = progressRepository.findById(progressKey);
        if (prev.isEmpty()) {
            return req.getStartEpoch();
        }
        BackupProgress p = prev.get();
        long resumeEpoch = p.getLastEpoch() - req.getStepSeconds();
        // Only resume if it's still inside the current request's range.
        if (resumeEpoch <= req.getStartEpoch()
                && (req.getEndEpoch() == null || resumeEpoch >= req.getEndEpoch())) {
            log.info("Resuming '{}' from epoch {} (lastEpoch={} iterationsDone={} snapshotsSaved={})",
                    progressKey, resumeEpoch, p.getLastEpoch(),
                    p.getIterationsDone(), p.getSnapshotsSaved());
            return resumeEpoch;
        }
        if (req.getEndEpoch() != null && resumeEpoch < req.getEndEpoch()) {
            // Entire previous run is past the new endEpoch — already complete.
            return resumeEpoch;
        }
        log.info("Stored progress for '{}' (lastEpoch={}) is outside the requested range [{}..{}], starting fresh from {}",
                progressKey, p.getLastEpoch(), req.getEndEpoch(), req.getStartEpoch(), req.getStartEpoch());
        return req.getStartEpoch();
    }

    /**
     * Pull every snapshot for one market from polybacktest, paging by offset
     * until {@code offset >= total}, and persist new ones.
     *
     * If a transient error breaks the loop part-way, the {@link BackfillResult}
     * carries {@code expectedTotal} so the caller can warn loudly when we did
     * not cover the full set. A re-run will catch up via in-memory dedup.
     */
    private BackfillResult backfillMarket(String slug, Market market, String coin) {
        UUID dbMarketId = market.getId();
        Set<Instant> existingTimes = persistence.loadExistingSnapshotTimes(dbMarketId);

        long saved = 0;
        long skipped = 0;
        int pages = 0;
        int offset = 0;
        Integer expectedTotal = null;

        while (true) {
            if (cancelRequested.get()) break;

            PolyBacktestSnapshotPage page =
                    client.fetchSnapshotsPage(market.getMarketId(), coin, offset);
            pages++;
            if (expectedTotal == null && page.getTotal() != null) {
                expectedTotal = page.getTotal();
            }

            List<PolyBacktestSnapshot> rows = page.snapshotsOrEmpty();
            if (log.isDebugEnabled()) {
                log.debug("[{}] page offset={} size={} total={}",
                        slug, offset, rows.size(), page.getTotal());
            }
            if (rows.isEmpty()) break;

            List<MarketSnapshot> toSave = new ArrayList<>(rows.size());
            for (PolyBacktestSnapshot s : rows) {
                if (s.getTime() == null) {
                    skipped++;
                    continue;
                }
                if (existingTimes.contains(s.getTime())) {
                    skipped++;
                    continue;
                }
                MarketSnapshot snap = MarketSnapshot.builder()
                        .market(market)
                        .time(s.getTime())
                        .coinPrice(s.getBtcPrice())
                        .priceUp(s.getPriceUp())
                        .priceDown(s.getPriceDown())
                        .orderbookUp(s.getOrderbookUp())
                        .orderbookDown(s.getOrderbookDown())
                        .build();
                toSave.add(snap);
                existingTimes.add(s.getTime()); // dedup within the same run
            }

            if (!toSave.isEmpty()) {
                for (int i = 0; i < toSave.size(); i += DB_BATCH_SIZE) {
                    int end = Math.min(i + DB_BATCH_SIZE, toSave.size());
                    persistence.saveAll(toSave.subList(i, end));
                }
                saved += toSave.size();
            }

            offset += rows.size();

            // Stop once we've consumed the full result set.
            Integer total = page.getTotal();
            if (total != null && offset >= total) break;
            // Defensive: server returned fewer than requested → last page.
            if (rows.size() < client.getPageSize()) break;
        }

        return new BackfillResult(saved, skipped, pages, expectedTotal);
    }

    private record BackfillResult(long saved, long skipped, int pages, Integer expectedTotal) {}

    /**
     * Persistence helpers carved out so transaction boundaries are explicit
     * and don't leak into the long-running outer loop.
     */
    @Service
    @RequiredArgsConstructor
    public static class SnapshotBackupPersistence {
        private final MarketSnapshotRepository snapshotRepository;
        private final BackupProgressRepository progressRepository;
        private final org.springframework.jdbc.core.JdbcTemplate jdbc;

        @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
        public Set<Instant> loadExistingSnapshotTimes(UUID marketId) {
            List<java.sql.Timestamp> rows = jdbc.queryForList(
                    "SELECT time FROM market_snapshots WHERE market_id = ?",
                    java.sql.Timestamp.class,
                    marketId);
            Set<Instant> out = new HashSet<>(rows.size() * 2);
            for (java.sql.Timestamp t : rows) {
                out.add(t.toInstant());
            }
            return out;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
        public long countSnapshotsForMarket(UUID marketId) {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM market_snapshots WHERE market_id = ?",
                    Long.class, marketId);
            return count != null ? count : 0L;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void saveAll(List<MarketSnapshot> snapshots) {
            snapshotRepository.saveAll(snapshots);
        }

        /**
         * Upsert the progress marker for a given run key. Failure here is
         * logged but never propagated — losing one heartbeat is fine; the
         * run will overwrite it on the next iteration.
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void upsertProgress(String key, BackupRequest req, long lastEpoch,
                                   String lastSlug, int iterations,
                                   long snapshotsSaved, long startedAtMs) {
            try {
                BackupProgress p = progressRepository.findById(key).orElseGet(() -> {
                    BackupProgress fresh = BackupProgress.builder()
                            .id(key)
                            .startEpoch(req.getStartEpoch())
                            .endEpoch(req.getEndEpoch() != null ? req.getEndEpoch() : 0L)
                            .startedAt(Instant.ofEpochMilli(startedAtMs))
                            .build();
                    return fresh;
                });
                p.setLastEpoch(lastEpoch);
                p.setLastSlug(lastSlug);
                p.setIterationsDone(iterations);
                p.setSnapshotsSaved(snapshotsSaved);
                // Refresh the stated range in case the request changed across restarts
                p.setStartEpoch(req.getStartEpoch());
                p.setEndEpoch(req.getEndEpoch() != null ? req.getEndEpoch() : 0L);
                progressRepository.save(p);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(SnapshotBackupService.class)
                        .warn("Failed to persist backup progress for {}: {}", key, e.getMessage());
            }
        }
    }
}
