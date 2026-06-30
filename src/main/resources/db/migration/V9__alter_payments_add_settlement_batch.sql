ALTER TABLE payments
    ADD CONSTRAINT fk_payments_settlement_batch
    FOREIGN KEY (settlement_batch_id) REFERENCES settlement_batches(id);

CREATE INDEX idx_payments_settlement_batch ON payments(settlement_batch_id);
