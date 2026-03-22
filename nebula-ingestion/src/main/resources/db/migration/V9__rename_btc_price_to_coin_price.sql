-- Rename BTC-specific price columns to generic coin price columns
-- to support ETH, SOL and future coins

ALTER TABLE markets RENAME COLUMN btc_price_start TO coin_price_start;
ALTER TABLE markets RENAME COLUMN btc_price_end TO coin_price_end;

ALTER TABLE market_snapshots RENAME COLUMN btc_price TO coin_price;
