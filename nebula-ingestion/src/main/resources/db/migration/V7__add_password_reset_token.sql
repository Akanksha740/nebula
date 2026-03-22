-- Add password reset token columns to customers table
ALTER TABLE customers ADD COLUMN IF NOT EXISTS password_reset_token VARCHAR(255);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS password_reset_token_expiry TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_customers_password_reset_token ON customers(password_reset_token);
