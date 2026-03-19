#!/bin/bash
# Nebula Production Deployment Script
# Usage: ./deploy.sh
# Run this on your Hetzner server after cloning the repo and filling in .env

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log()  { echo -e "${GREEN}[nebula]${NC} $1"; }
warn() { echo -e "${YELLOW}[nebula]${NC} $1"; }
fail() { echo -e "${RED}[nebula]${NC} $1"; exit 1; }

# ── Preflight checks ──────────────────────────────────────────────────────────

command -v docker >/dev/null 2>&1 || fail "Docker is not installed."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is not installed."

[ -f .env ] || fail ".env not found. Copy .env.prod to .env and fill in your values."

# Verify no placeholder values remain
grep -q "change_me" .env && fail ".env still contains placeholder values. Fill them in first."

# ── Pull latest code ──────────────────────────────────────────────────────────

log "Pulling latest code..."
git pull origin main

# ── Build images ──────────────────────────────────────────────────────────────

log "Building Docker images (this takes a few minutes on first run)..."
docker compose build --no-cache

# ── Start / restart services ─────────────────────────────────────────────────

log "Starting services..."
docker compose up -d

# ── Wait for health checks ────────────────────────────────────────────────────

log "Waiting for API to become healthy..."
RETRIES=20
until docker inspect nebula-api --format='{{.State.Health.Status}}' 2>/dev/null | grep -q "healthy"; do
    RETRIES=$((RETRIES - 1))
    [ $RETRIES -eq 0 ] && fail "API did not become healthy in time. Run: docker compose logs api"
    sleep 5
done
log "API is healthy."

log "Waiting for Ingestion to become healthy..."
RETRIES=20
until docker inspect nebula-ingestion --format='{{.State.Health.Status}}' 2>/dev/null | grep -q "healthy"; do
    RETRIES=$((RETRIES - 1))
    [ $RETRIES -eq 0 ] && fail "Ingestion did not become healthy. Run: docker compose logs ingestion"
    sleep 5
done
log "Ingestion is healthy."

# ── Done ──────────────────────────────────────────────────────────────────────

echo ""
log "=== Deployment complete ==="
log "API health:  http://localhost:8080/v1/health"
log "API swagger: http://localhost:8080/swagger-ui.html"
echo ""
warn "Make sure Caddy is running: sudo systemctl status caddy"