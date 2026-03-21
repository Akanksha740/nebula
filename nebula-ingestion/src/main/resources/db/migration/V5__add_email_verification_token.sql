-- Add email verification token columns to customers table
ALTER TABLE customers ADD COLUMN IF NOT EXISTS email_verification_token VARCHAR(255);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS email_verification_token_expiry TIMESTAMPTZ;

CREATE INDEX idx_customers_verification_token ON customers(email_verification_token);
