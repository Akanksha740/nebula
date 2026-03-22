-- Rename Stripe columns to generic payment provider columns
ALTER TABLE customers RENAME COLUMN stripe_customer_id TO payment_customer_id;
ALTER TABLE customers RENAME COLUMN stripe_subscription_id TO payment_subscription_id;

DROP INDEX IF EXISTS idx_customers_stripe_id;
CREATE INDEX IF NOT EXISTS idx_customers_payment_id ON customers(payment_customer_id);
