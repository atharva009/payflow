package com.payments.ledger;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

    List<LedgerEntry> findByPaymentId(UUID paymentId);

    @Query("SELECT le FROM LedgerEntry le WHERE le.accountId = :accountId ORDER BY le.createdAt DESC")
    List<LedgerEntry> findLatestByAccountId(@Param("accountId") UUID accountId, Pageable pageable);
}
