-- Second forced reset: previous deploy applied V15 which cleared progress,
-- but the run between then and this deploy accumulated a new last_epoch.
-- This migration clears it again so the next startup walks the full range
-- with the latest pagination fix (don't depend on `total`) and the cleaner
-- `received` accounting.
--
-- Idempotent: deleting from an empty table is a no-op.

DELETE FROM backup_progress;
