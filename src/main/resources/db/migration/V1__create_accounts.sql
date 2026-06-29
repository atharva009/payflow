CREATE TABLE accounts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    UUID NOT NULL,
    currency    VARCHAR(3) NOT NULL DEFAULT 'USD',
    balance     NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT chk_positive_balance CHECK (balance >= 0)
);
