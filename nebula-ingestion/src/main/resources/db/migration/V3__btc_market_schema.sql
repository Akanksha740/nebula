-- BTC Market schema with all required fields
DROP TABLE IF EXISTS market_snapshots CASCADE;
DROP TABLE IF EXISTS markets CASCADE;

-- Markets table with full BTC Up/Down market info
CREATE TABLE markets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(255) NOT NULL UNIQUE,
    market_id VARCHAR(100),
    event_id VARCHAR(100),
    market_type VARCHAR(20),
    condition_id VARCHAR(255),
    clob_token_up VARCHAR(255),
    clob_token_down VARCHAR(255),
    start_time TIMESTAMPTZ,
    end_time TIMESTAMPTZ,
    btc_price_start DECIMAL(20, 8),
    btc_price_end DECIMAL(20, 8),
    winner VARCHAR(10),
    final_volume DECIMAL(20, 2),
    final_liquidity DECIMAL(20, 2),
    resolved_at TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_markets_slug ON markets(slug);
CREATE INDEX idx_markets_active ON markets(active);
CREATE INDEX idx_markets_market_id ON markets(market_id);

-- Snapshots table with price and orderbook data
CREATE TABLE market_snapshots (
    id BIGSERIAL PRIMARY KEY,
    market_id UUID NOT NULL REFERENCES markets(id) ON DELETE CASCADE,
    time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    btc_price DECIMAL(20, 8),
    price_up DECIMAL(10, 6),
    price_down DECIMAL(10, 6),
    orderbook_up JSONB,
    orderbook_down JSONB,
    volume DECIMAL(20, 2),
    liquidity DECIMAL(20, 2)
);

CREATE INDEX idx_snapshots_market_time ON market_snapshots(market_id, time DESC);
CREATE INDEX idx_snapshots_time ON market_snapshots(time DESC);

-- Auto-update timestamp trigger
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

DROP TRIGGER IF EXISTS markets_updated_at ON markets;
CREATE TRIGGER markets_updated_at
    BEFORE UPDATE ON markets
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at();
