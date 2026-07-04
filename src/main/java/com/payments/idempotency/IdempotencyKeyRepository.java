package com.payments.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {
    void deleteByExpiresAtBefore(Instant cutoff);
}
