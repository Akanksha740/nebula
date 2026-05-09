package com.nebula.ingestion.service;

import lombok.Builder;
import lombok.Value;

/**
 * Parameters for a snapshot backup run.
 *
 * The slug pattern is: {coinPrefix}-updown-{interval}-{epoch}
 *   e.g.  btc-updown-5m-1778320800
 *
 * Iteration walks epoch downwards by {@code stepSeconds} until one of the
 * stop conditions in {@link SnapshotBackupService} fires.
 */
@Value
@Builder
public class BackupRequest {

    /** Slug prefix — "btc", "eth", or "sol". Lower-cased. */
    String coinPrefix;

    /** Slug interval token — "5m", "15m", "4h", etc. */
    String interval;

    /** Coin query value sent to polybacktest, e.g. "BTC". */
    String coin;

    /** First epoch (inclusive). Walks downward from here. */
    long startEpoch;

    /** Optional lower bound (inclusive). Stops when current epoch < this. */
    Long endEpoch;

    /** Decrement applied per iteration (seconds). */
    int stepSeconds;

    /** Hard cap on iterations (safety). */
    int maxIterations;

    /** Stop after this many slugs in a row whose markets aren't in our DB. */
    int maxConsecutiveMissing;

    public BackupRequest normalized() {
        String prefix = coinPrefix == null ? "btc" : coinPrefix.toLowerCase();
        String iv = interval == null ? "5m" : interval.toLowerCase();
        String c = coin == null ? prefix.toUpperCase() : coin.toUpperCase();
        int step = stepSeconds <= 0 ? 300 : stepSeconds;
        int maxIter = maxIterations <= 0 ? 5_000 : maxIterations;
        int maxMissing = maxConsecutiveMissing <= 0 ? 100 : maxConsecutiveMissing;
        return BackupRequest.builder()
                .coinPrefix(prefix)
                .interval(iv)
                .coin(c)
                .startEpoch(startEpoch)
                .endEpoch(endEpoch)
                .stepSeconds(step)
                .maxIterations(maxIter)
                .maxConsecutiveMissing(maxMissing)
                .build();
    }
}
