CREATE TABLE settlement_batches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_date DATE NOT NULL UNIQUE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_count   INTEGER NOT NULL DEFAULT 0,
    gross_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,
    net_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
    processor_ref   VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    submitted_at    TIMESTAMPTZ,
    settled_at      TIMESTAMPTZ,
    version         BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_settlement_status CHECK (
        status IN ('PENDING', 'CALCULATING', 'SUBMITTED', 'SETTLED', 'FAILED')
    ),
    CONSTRAINT chk_net_positive CHECK (net_amount >= 0),
    CONSTRAINT chk_net_lte_gross CHECK (net_amount <= gross_amount)
);
