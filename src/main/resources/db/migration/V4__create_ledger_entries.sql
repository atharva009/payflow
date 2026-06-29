CREATE TABLE ledger_entries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id    UUID NOT NULL REFERENCES payments(id),
    account_id    UUID NOT NULL REFERENCES accounts(id),
    entry_type    VARCHAR(10) NOT NULL,
    amount        NUMERIC(19,4) NOT NULL,
    balance_after NUMERIC(19,4) NOT NULL,
    description   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_amount CHECK (amount > 0),
    CONSTRAINT chk_balance_after CHECK (balance_after >= 0)
);

CREATE INDEX idx_ledger_account_created ON ledger_entries(account_id, created_at);
