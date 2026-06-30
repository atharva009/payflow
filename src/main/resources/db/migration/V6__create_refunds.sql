CREATE TABLE refunds (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id    UUID NOT NULL UNIQUE REFERENCES payments(id),
    amount        NUMERIC(19,4) NOT NULL,
    refund_type   VARCHAR(10) NOT NULL,
    reason        TEXT,
    processor_ref VARCHAR(255),
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_refund_type CHECK (refund_type IN ('VOID', 'REVERSAL')),
    CONSTRAINT chk_refund_amount CHECK (amount > 0),
    CONSTRAINT chk_refund_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);
