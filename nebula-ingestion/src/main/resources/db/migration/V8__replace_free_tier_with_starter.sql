-- Replace any remaining FREE tier with STARTER
UPDATE customers SET tier = 'STARTER' WHERE tier = 'FREE';

-- Update default to STARTER
ALTER TABLE customers ALTER COLUMN tier SET DEFAULT 'STARTER';
