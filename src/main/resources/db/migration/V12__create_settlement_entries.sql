CREATE TABLE settlement_entries (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id          UUID NOT NULL REFERENCES settlement_batches(id),
    payment_id        UUID NOT NULL REFERENCES payments(id),
    source_account_id UUID NOT NULL,
    dest_account_id   UUID NOT NULL,
    amount            NUMERIC(19,4) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_settlement_entry_amount CHECK (amount > 0),
    CONSTRAINT uq_settlement_entry_payment UNIQUE (payment_id)
);

CREATE INDEX idx_settlement_entries_batch
    ON settlement_entries(batch_id);

CREATE INDEX idx_settlement_entries_accounts
    ON settlement_entries(source_account_id, dest_account_id);
