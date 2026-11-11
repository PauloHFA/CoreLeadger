package com.coreledger.domain.repository;

import com.coreledger.domain.model.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Page<LedgerEntry> findByAccountIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID accountId, OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    @Query("""
            select coalesce(sum(case when e.entryType = 'CREDIT' then e.amountCents else -e.amountCents end), 0)
            from LedgerEntry e where e.accountId = :accountId
            """)
    long sumSignedByAccount(@Param("accountId") UUID accountId);
}
