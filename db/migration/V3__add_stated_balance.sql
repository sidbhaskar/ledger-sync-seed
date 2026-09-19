-- Add stated_balance to capture the bank-quoted balance from each SMS.
-- Used by reconciliation to verify the ledger matches reality.
-- Nullable: email alerts and some SMS formats do not quote a balance.
ALTER TABLE ledger ADD COLUMN IF NOT EXISTS stated_balance DECIMAL(14, 2);
