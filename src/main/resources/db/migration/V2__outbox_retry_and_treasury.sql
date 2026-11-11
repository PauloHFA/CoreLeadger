ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS last_error TEXT;

-- Central Treasury account for strict double-entry balance on initial funding
INSERT INTO accounts (id, holder_name, account_number, balance_cents, version)
VALUES ('00000000-0000-0000-0000-000000000000', 'CoreLedger Central Treasury', '0000-TREASURY', 100000000000000, 0)
ON CONFLICT (id) DO NOTHING;
