package com.payments.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationReportRepository extends JpaRepository<ReconciliationReport, UUID> {
    Optional<ReconciliationReport> findByReportDate(LocalDate date);
}
