package com.payments.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private static final String REDIS_PREFIX = "idempotency:";

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyKeyRepository idempotencyKeyRepository,
                              StringRedisTemplate redisTemplate,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Layer 1 (Redis) then Layer 2 (DB). Returns empty if there is no live prior record.
     * Any Redis I/O failure propagates to the caller (the filter), which fails closed
     * with {@code CacheUnavailableException} — the system never falls through to DB-only mode.
     */
    public Optional<CachedResponse> findCachedResponse(String idempotencyKey) {
        String redisKey = REDIS_PREFIX + idempotencyKey;

        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            return Optional.of(deserialize(cached));
        }

        Optional<IdempotencyKey> entityOpt = idempotencyKeyRepository.findById(idempotencyKey);
        if (entityOpt.isEmpty()) {
            return Optional.empty();
        }

        IdempotencyKey entity = entityOpt.get();
        Duration remainingTtl = Duration.between(Instant.now(), entity.getExpiresAt());
        if (remainingTtl.isNegative() || remainingTtl.isZero()) {
            // Expired — treat as a brand new request.
            return Optional.empty();
        }

        // Cache re-population on DB hit.
        redisTemplate.opsForValue().set(redisKey, entity.getResponseBody(), remainingTtl);
        return Optional.of(deserialize(entity.getResponseBody()));
    }

    /**
     * Store the result after a successful payment creation. Writes the authoritative DB
     * row and (when the TTL is still positive) repopulates Redis. The serialized
     * {@link CachedResponse} — including the request hash — is stored as the response body.
     */
    public void cacheResponse(String idempotencyKey, UUID paymentId,
                              int statusCode, String body,
                              String requestHash, Instant expiresAt) {
        CachedResponse cachedResponse = new CachedResponse(statusCode, body, requestHash);
        String serialized = serialize(cachedResponse);

        IdempotencyKey entity = IdempotencyKey.create(
                idempotencyKey, paymentId, statusCode, serialized, expiresAt);
        idempotencyKeyRepository.save(entity);

        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (!ttl.isNegative() && !ttl.isZero()) {
            redisTemplate.opsForValue().set(REDIS_PREFIX + idempotencyKey, serialized, ttl);
        }
    }

    private String serialize(CachedResponse cachedResponse) {
        try {
            return objectMapper.writeValueAsString(cachedResponse);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize CachedResponse", e);
        }
    }

    private CachedResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, CachedResponse.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize CachedResponse", e);
        }
    }
}
