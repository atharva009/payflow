package com.payments.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, UUID> {

    Optional<SettlementBatch> findBySettlementDate(LocalDate date);

    @Query("SELECT sb FROM SettlementBatch sb WHERE sb.status = :status")
    List<SettlementBatch> findByStatus(@Param("status") SettlementBatchStatus status);
}
