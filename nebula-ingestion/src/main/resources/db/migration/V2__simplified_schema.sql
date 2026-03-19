-- Simplified schema for POC
-- Drop old tables and triggers
DROP TRIGGER IF EXISTS update_markets_timestamp ON markets;
DROP FUNCTION IF EXISTS update_last_updated_column() CASCADE;

DROP TABLE IF EXISTS market_snapshots CASCADE;
DROP TABLE IF EXISTS markets CASCADE;

-- Simplified markets table - only tracked markets
CREATE TABLE markets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(255) NOT NULL UNIQUE,
    title VARCHAR(500),
    status VARCHAR(50),
    end_date TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_markets_slug ON markets(slug);
CREATE INDEX idx_markets_active ON markets(active);

-- Simplified snapshots table
CREATE TABLE market_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    market_id UUID NOT NULL REFERENCES markets(id) ON DELETE CASCADE,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    outcome_prices JSONB,
    volume DECIMAL(20, 2),
    liquidity DECIMAL(20, 2),
    raw_data JSONB
);

CREATE INDEX idx_snapshots_market_time ON market_snapshots(market_id, captured_at DESC);
CREATE INDEX idx_snapshots_captured_at ON market_snapshots(captured_at DESC);

-- Auto-update timestamp trigger
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER markets_updated_at
    BEFORE UPDATE ON markets
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at();
