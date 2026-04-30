ALTER TABLE customers ADD COLUMN crypto_subscription_id VARCHAR(255);
ALTER TABLE customers ADD COLUMN crypto_subscription_expires_at TIMESTAMPTZ;
