package com.payments.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.payments.exception.CacheUnavailableException;
import com.payments.exception.ConcurrentRequestException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Two-layer idempotency for {@code POST /api/v1/payments}. Runs after the Spring Security
 * chain. Owns: idempotency-key validation, raw-body SHA-256 hashing, the fail-closed cache
 * lookup, the replay short-circuit, the Redlock, and the cache write after a successful create.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String TARGET_PATH = "/api/v1/payments";
    private static final String HEADER = "Idempotency-Key";
    private static final int HTTP_CREATED_ACCEPTED = HttpServletResponse.SC_ACCEPTED; // 202

    private final IdempotencyService idempotencyService;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(IdempotencyService idempotencyService,
                             RedissonClient redissonClient,
                             ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (!isTargetRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY");
            return;
        }
        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException ex) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
            return;
        }

        byte[] bodyBytes = StreamUtils.copyToByteArray(request.getInputStream());
        String requestHash = sha256Hex(bodyBytes);
        HttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request, bodyBytes);

        // Layer 1 + 2 lookup. Fail closed: any Redis failure becomes 503, never DB-only.
        Optional<CachedResponse> existing;
        try {
            existing = idempotencyService.findCachedResponse(key);
        } catch (RuntimeException ex) {
            throw new CacheUnavailableException("Idempotency cache unavailable", ex);
        }

        if (existing.isPresent()) {
            replayOrConflict(response, existing.get(), requestHash);
            return;
        }

        // Concurrent-request protection — Redlock.
        RLock lock = redissonClient.getLock("lock:idempotency:" + key);
        boolean acquired;
        try {
            acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ConcurrentRequestException(key);
        }
        if (!acquired) {
            throw new ConcurrentRequestException(key);
        }

        try {
            ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
            filterChain.doFilter(cachedRequest, responseWrapper);

            int status = responseWrapper.getStatus();
            byte[] responseBytes = responseWrapper.getContentAsByteArray();

            if (status == HTTP_CREATED_ACCEPTED && responseBytes.length > 0) {
                String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
                UUID paymentId = extractPaymentId(responseBytes);
                if (paymentId != null) {
                    Instant expiresAt = Instant.now().plus(Duration.ofHours(24));
                    idempotencyService.cacheResponse(
                            key, paymentId, status, responseBody, requestHash, expiresAt);
                }
            }
            responseWrapper.copyBodyToResponse();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void replayOrConflict(HttpServletResponse response, CachedResponse cached, String requestHash)
            throws IOException {
        if (!cached.requestHash().equals(requestHash)) {
            writeError(response, HttpServletResponse.SC_CONFLICT, "IDEMPOTENCY_CONFLICT");
            return;
        }
        response.setStatus(cached.statusCode());
        response.setContentType("application/json");
        response.getWriter().write(cached.body());
    }

    private UUID extractPaymentId(byte[] responseBytes) {
        try {
            JsonNode node = objectMapper.readTree(responseBytes);
            JsonNode id = node.get("id");
            return id == null ? null : UUID.fromString(id.asText());
        } catch (JacksonException | IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isTargetRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && TARGET_PATH.equals(request.getRequestURI());
    }

    private void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Buffers the request body so both this filter and the controller can read it. */
    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream backing = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return backing.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return backing.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }
}
