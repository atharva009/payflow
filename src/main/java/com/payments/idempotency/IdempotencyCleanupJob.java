package com.payments.idempotency;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class IdempotencyCleanupJob {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public IdempotencyCleanupJob(IdempotencyKeyRepository idempotencyKeyRepository) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(name = "idempotencyCleanup", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    @Transactional
    public void cleanupExpiredIdempotencyKeys() {
        idempotencyKeyRepository.deleteByExpiresAtBefore(Instant.now());
    }
}
