package com.payments.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementEntryRepository extends JpaRepository<SettlementEntry, UUID> {

    List<SettlementEntry> findByBatchId(UUID batchId);

    Optional<SettlementEntry> findByPaymentId(UUID paymentId);

    /**
     * Netting pushed to the database (avoids OOM on large datasets). For each ordered
     * counterparty pair, nets the two directions and returns only positive net amounts.
     * Returns rows of [party_a (UUID), party_b (UUID), net_amount (NUMERIC)].
     */
    @Query(value = """
            SELECT
                LEAST(a.source_account_id, a.dest_account_id) AS party_a,
                GREATEST(a.source_account_id, a.dest_account_id) AS party_b,
                SUM(a.amount) - COALESCE(SUM(b.amount), 0) AS net_amount
            FROM settlement_entries a
            LEFT JOIN settlement_entries b
                ON b.source_account_id = a.dest_account_id
                AND b.dest_account_id = a.source_account_id
                AND b.batch_id = a.batch_id
            WHERE a.batch_id = :batchId
            GROUP BY party_a, party_b
            HAVING SUM(a.amount) - COALESCE(SUM(b.amount), 0) > 0
            """, nativeQuery = true)
    List<Object[]> computeNetting(@Param("batchId") UUID batchId);
}
