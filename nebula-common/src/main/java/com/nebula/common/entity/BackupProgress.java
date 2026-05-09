package com.nebula.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Persistent progress marker for the historical snapshot backup job.
 *
 * Keyed by a run identity like {@code "btc-updown-5m"}. {@code lastEpoch} is
 * the most recent epoch the job successfully finished — on restart, the
 * service resumes from {@code lastEpoch - stepSeconds}.
 */
@Entity
@Table(name = "backup_progress")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BackupProgress {

    @Id
    @Column(length = 100)
    private String id;

    @Column(name = "start_epoch", nullable = false)
    private Long startEpoch;

    @Column(name = "end_epoch", nullable = false)
    private Long endEpoch;

    @Column(name = "last_epoch", nullable = false)
    private Long lastEpoch;

    @Column(name = "last_slug", nullable = false)
    private String lastSlug;

    @Column(name = "iterations_done", nullable = false)
    @Builder.Default
    private Integer iterationsDone = 0;

    @Column(name = "snapshots_saved", nullable = false)
    @Builder.Default
    private Long snapshotsSaved = 0L;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (startedAt == null) startedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
