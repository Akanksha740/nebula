-- Add coin and resolved columns to markets table

ALTER TABLE markets ADD COLUMN IF NOT EXISTS coin VARCHAR(10);
ALTER TABLE markets ADD COLUMN IF NOT EXISTS resolved BOOLEAN NOT NULL DEFAULT false;

-- Update existing records: extract coin from slug
UPDATE markets SET coin = 'BTC' WHERE slug LIKE 'btc-%' AND coin IS NULL;
UPDATE markets SET coin = 'ETH' WHERE slug LIKE 'eth-%' AND coin IS NULL;
UPDATE markets SET coin = 'SOL' WHERE slug LIKE 'sol-%' AND coin IS NULL;

-- Update resolved based on resolved_at
UPDATE markets SET resolved = true WHERE resolved_at IS NOT NULL;

-- Make coin NOT NULL after populating
ALTER TABLE markets ALTER COLUMN coin SET NOT NULL;

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_markets_coin ON markets(coin);
CREATE INDEX IF NOT EXISTS idx_markets_resolved ON markets(resolved);
