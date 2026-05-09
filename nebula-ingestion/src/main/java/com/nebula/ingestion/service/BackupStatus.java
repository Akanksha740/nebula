package com.nebula.ingestion.service;

import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Snapshot of an ongoing or finished backup run, exposed via the controller
 * for monitoring. All fields are nullable when not yet known.
 */
@Value
@Builder
@With
public class BackupStatus {

    BackupRequest request;
    boolean running;
    Long currentEpoch;
    String currentSlug;
    int iterations;
    int marketsHit;
    int marketsMissing;
    long snapshotsSaved;
    long snapshotsSkipped;
    Long startedAtMs;
    Long finishedAtMs;
    String error;

    public static BackupStatus idle() {
        return BackupStatus.builder().running(false).build();
    }

    public static BackupStatus starting(BackupRequest req) {
        return BackupStatus.builder()
                .request(req)
                .running(true)
                .currentEpoch(req.getStartEpoch())
                .startedAtMs(System.currentTimeMillis())
                .build();
    }
}
