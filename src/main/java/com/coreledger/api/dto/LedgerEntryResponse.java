package com.coreledger.api.dto;

import com.coreledger.domain.model.LedgerEntry;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LedgerEntryResponse(UUID id, UUID transactionId, UUID accountId, String entryType,
                                  long amountCents, long signedAmountCents, OffsetDateTime createdAt) {
    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(e.getId(), e.getTransactionId(), e.getAccountId(), e.getEntryType(),
                e.getAmountCents(), e.signedAmountCents(), e.getCreatedAt());
    }
}
