CREATE TABLE reconciliation_reports (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_date       DATE NOT NULL UNIQUE,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_processed   INTEGER NOT NULL DEFAULT 0,
    total_matched     INTEGER NOT NULL DEFAULT 0,
    total_discrepant  INTEGER NOT NULL DEFAULT 0,
    discrepancies     JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at      TIMESTAMPTZ,
    version           BIGINT NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_recon_status CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')
    )
);
