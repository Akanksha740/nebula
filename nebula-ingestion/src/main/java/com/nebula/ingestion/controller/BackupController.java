package com.nebula.ingestion.controller;

import com.nebula.common.dto.response.ApiResponse;
import com.nebula.ingestion.service.BackupRequest;
import com.nebula.ingestion.service.BackupStatus;
import com.nebula.ingestion.service.SnapshotBackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Manual control endpoints for the historical snapshot backup job.
 *
 * Default request walks btc-updown-5m-{epoch} starting from 1778320800,
 * decrementing by 300 seconds per iteration, stopping after 100 consecutive
 * missing markets or 5000 iterations — whichever comes first.
 *
 * All endpoints are gated by ApiKeyFilter (X-API-Key header).
 */
@RestController
@RequestMapping("/api/backup")
@RequiredArgsConstructor
public class BackupController {

    private static final long DEFAULT_START_EPOCH = 1_778_320_800L;

    private final SnapshotBackupService backupService;

    /**
     * Kick off an async backup. Returns the initial status; poll /status for
     * progress.
     *
     * Query params (all optional):
     *   coinPrefix=btc | eth | sol            (default btc)
     *   interval=5m | 15m | ...               (default 5m)
     *   coin=BTC | ETH | SOL                  (default uppercase coinPrefix)
     *   startEpoch=<long>                     (default 1778320800)
     *   endEpoch=<long>                       (optional; stops when epoch < this)
     *   stepSeconds=<int>                     (default 300)
     *   maxIterations=<int>                   (default 5000)
     *   maxConsecutiveMissing=<int>           (default 100)
     */
    @PostMapping("/start")
    public ResponseEntity<ApiResponse<BackupStatus>> start(
            @RequestParam(required = false) String coinPrefix,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false) String coin,
            @RequestParam(required = false) Long startEpoch,
            @RequestParam(required = false) Long endEpoch,
            @RequestParam(required = false) Integer stepSeconds,
            @RequestParam(required = false) Integer maxIterations,
            @RequestParam(required = false) Integer maxConsecutiveMissing) {

        BackupRequest req = BackupRequest.builder()
                .coinPrefix(coinPrefix)
                .interval(interval)
                .coin(coin)
                .startEpoch(startEpoch != null ? startEpoch : DEFAULT_START_EPOCH)
                .endEpoch(endEpoch)
                .stepSeconds(stepSeconds != null ? stepSeconds : 0)
                .maxIterations(maxIterations != null ? maxIterations : 0)
                .maxConsecutiveMissing(maxConsecutiveMissing != null ? maxConsecutiveMissing : 0)
                .build();

        BackupStatus initial = backupService.start(req);
        return ResponseEntity.ok(ApiResponse.success(initial));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<BackupStatus>> status() {
        return ResponseEntity.ok(ApiResponse.success(backupService.getStatus()));
    }

    @PostMapping("/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancel() {
        boolean wasRunning = backupService.isRunning();
        backupService.requestCancel();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "wasRunning", wasRunning,
                "message", wasRunning ? "Cancel requested" : "No backup running"
        )));
    }
}
