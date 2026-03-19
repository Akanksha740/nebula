#!/bin/bash
# Database backup script for Nebula
# Run daily via cron: 0 2 * * * /path/to/backup.sh

set -e

BACKUP_DIR="${BACKUP_DIR:-/var/backups/nebula}"
DB_CONTAINER="${DB_CONTAINER:-nebula-postgres}"
DB_NAME="${DB_NAME:-nebula}"
DB_USER="${DB_USER:-nebula}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
B2_BUCKET="${B2_BUCKET:-}"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="nebula_backup_${TIMESTAMP}.sql.gz"

echo "Starting backup at $(date)"

# Create backup directory if it doesn't exist
mkdir -p "$BACKUP_DIR"

# Perform backup
echo "Creating database backup..."
docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "$BACKUP_DIR/$BACKUP_FILE"

# Check backup file size
BACKUP_SIZE=$(ls -lh "$BACKUP_DIR/$BACKUP_FILE" | awk '{print $5}')
echo "Backup created: $BACKUP_FILE ($BACKUP_SIZE)"

# Upload to Backblaze B2 if configured
if [ -n "$B2_BUCKET" ]; then
    echo "Uploading to Backblaze B2..."
    b2 upload-file "$B2_BUCKET" "$BACKUP_DIR/$BACKUP_FILE" "backups/$BACKUP_FILE"
    echo "Upload complete"
fi

# Clean up old local backups
echo "Cleaning up backups older than $RETENTION_DAYS days..."
find "$BACKUP_DIR" -name "nebula_backup_*.sql.gz" -mtime +$RETENTION_DAYS -delete

echo "Backup completed at $(date)"
