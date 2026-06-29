CREATE TABLE payments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key     VARCHAR(255) NOT NULL,
    source_account_id   UUID NOT NULL REFERENCES accounts(id),
    dest_account_id     UUID REFERENCES accounts(id),
    amount              NUMERIC(19,4) NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'USD',
    status              VARCHAR(30) NOT NULL,
    failure_reason      TEXT,
    processor_ref       VARCHAR(255),
    settlement_batch_id UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_payment_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_status CHECK (status IN (
        'PENDING', 'AUTHORIZED', 'CAPTURED', 'SETTLEMENT_QUEUED',
        'SETTLED', 'CANCELLED', 'FAILED', 'REFUNDED'
    ))
);

CREATE INDEX idx_payments_source_account ON payments(source_account_id);
CREATE INDEX idx_payments_status_created ON payments(status, created_at);
CREATE INDEX idx_payments_pending ON payments(created_at) WHERE status = 'PENDING';
