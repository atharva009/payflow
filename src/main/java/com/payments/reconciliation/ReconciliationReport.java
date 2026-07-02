package com.payments.reconciliation;

import com.payments.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "reconciliation_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationReport extends BaseEntity {

    @Column(name = "report_date", nullable = false, unique = true)
    private LocalDate reportDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReconciliationStatus status;

    @Column(name = "total_processed", nullable = false)
    private Integer totalProcessed;

    @Column(name = "total_matched", nullable = false)
    private Integer totalMatched;

    @Column(name = "total_discrepant", nullable = false)
    private Integer totalDiscrepant;

    // Bind the String as JSON so PostgreSQL accepts it into the jsonb column.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "discrepancies", columnDefinition = "jsonb")
    private String discrepancies;

    @Column(name = "completed_at")
    private Instant completedAt;

    public static ReconciliationReport create(LocalDate reportDate) {
        ReconciliationReport r = new ReconciliationReport();
        r.reportDate = reportDate;
        r.status = ReconciliationStatus.PENDING;
        r.totalProcessed = 0;
        r.totalMatched = 0;
        r.totalDiscrepant = 0;
        return r;
    }

    // Domain mutators (Step 9).

    public void markRunning() {
        this.status = ReconciliationStatus.RUNNING;
    }

    public void recordResults(int totalProcessed, int totalMatched, int totalDiscrepant,
                              String discrepancies) {
        this.totalProcessed = totalProcessed;
        this.totalMatched = totalMatched;
        this.totalDiscrepant = totalDiscrepant;
        this.discrepancies = discrepancies;
    }

    public void markCompleted(Instant completedAt) {
        this.status = ReconciliationStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    public void markFailed() {
        this.status = ReconciliationStatus.FAILED;
    }
}
