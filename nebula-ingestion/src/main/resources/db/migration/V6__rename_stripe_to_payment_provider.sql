-- Drop old Stripe columns (data already migrated to payment_customer_id / payment_subscription_id)
ALTER TABLE customers DROP COLUMN IF EXISTS stripe_customer_id;
ALTER TABLE customers DROP COLUMN IF EXISTS stripe_subscription_id;

DROP INDEX IF EXISTS idx_customers_stripe_id;
CREATE INDEX IF NOT EXISTS idx_customers_payment_id ON customers(payment_customer_id);
