-- One-time reset: the previous run terminated every market at page 1 because
-- polybacktest's `total` field is unreliable when include_orderbook=true.
-- Clearing the progress marker lets the next run re-walk the full range with
-- the pagination fix applied. Already-complete markets are skipped instantly
-- by the count-multiple heuristic, so this is much faster than a cold backfill.

DELETE FROM backup_progress;
