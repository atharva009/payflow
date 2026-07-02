package com.payments.idempotency;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    @Id
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "response_status", nullable = false)
    private Integer responseStatus;

    // Bind the String as JSON so PostgreSQL accepts it into the jsonb column
    // (a plain varchar bind is rejected: "column is of type jsonb but expression is character varying").
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", nullable = false, columnDefinition = "jsonb")
    private String responseBody;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public static IdempotencyKey create(String key, UUID paymentId,
                                         Integer responseStatus, String responseBody,
                                         Instant expiresAt) {
        IdempotencyKey ik = new IdempotencyKey();
        ik.idempotencyKey = key;
        ik.paymentId = paymentId;
        ik.responseStatus = responseStatus;
        ik.responseBody = responseBody;
        ik.expiresAt = expiresAt;
        return ik;
    }
}
