-- Seed initial accounts for testing
-- Note: In production, these would be created through the API

-- Customer account
INSERT INTO accounts (external_id, account_type, currency, balance, available_balance, hold_balance, status, created_at, updated_at, version)
VALUES ('acc_customer_001', 'CUSTOMER_WALLET', 'USD', 10000.0000, 10000.0000, 0.0000, 'ACTIVE', NOW(), NOW(), 0)
ON CONFLICT (external_id) DO NOTHING;

-- Merchant account
INSERT INTO accounts (external_id, account_type, currency, balance, available_balance, hold_balance, status, created_at, updated_at, version)
VALUES ('acc_merchant_001', 'MERCHANT_ACCOUNT', 'USD', 0.0000, 0.0000, 0.0000, 'ACTIVE', NOW(), NOW(), 0)
ON CONFLICT (external_id) DO NOTHING;

-- Settlement account
INSERT INTO accounts (external_id, account_type, currency, balance, available_balance, hold_balance, status, created_at, updated_at, version)
VALUES ('settlement_account_USD', 'SETTLEMENT_ACCOUNT', 'USD', 0.0000, 0.0000, 0.0000, 'ACTIVE', NOW(), NOW(), 0)
ON CONFLICT (external_id) DO NOTHING;

-- Fee account
INSERT INTO accounts (external_id, account_type, currency, balance, available_balance, hold_balance, status, created_at, updated_at, version)
VALUES ('fee_account_USD', 'FEE_ACCOUNT', 'USD', 0.0000, 0.0000, 0.0000, 'ACTIVE', NOW(), NOW(), 0)
ON CONFLICT (external_id) DO NOTHING;

