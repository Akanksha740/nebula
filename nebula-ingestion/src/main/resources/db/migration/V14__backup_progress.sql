-- Tracks how far the historical snapshot backup has walked, keyed by
-- "{coinPrefix}-updown-{interval}" (e.g. "btc-updown-5m"). On restart, the
-- backup resumes from one step below `last_epoch` instead of starting over.
--
-- One row per (coin × interval) backup configuration. Updated after each
-- successfully-processed slug.

CREATE TABLE IF NOT EXISTS backup_progress (
    id               VARCHAR(100)  PRIMARY KEY,           -- e.g. "btc-updown-5m"
    start_epoch      BIGINT        NOT NULL,              -- the run's startEpoch (sanity)
    end_epoch        BIGINT        NOT NULL,              -- the run's endEpoch (sanity)
    last_epoch       BIGINT        NOT NULL,              -- last epoch we finished
    last_slug        VARCHAR(255)  NOT NULL,
    iterations_done  INTEGER       NOT NULL DEFAULT 0,
    snapshots_saved  BIGINT        NOT NULL DEFAULT 0,
    started_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
