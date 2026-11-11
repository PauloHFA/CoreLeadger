package com.coreledger.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    public static final String DEBIT = "DEBIT";
    public static final String CREDIT = "CREDIT";

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "entry_type", nullable = false)
    private String entryType;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected LedgerEntry() {
    }

    public LedgerEntry(UUID transactionId, UUID accountId, String entryType, long amountCents) {
        this.id = UUID.randomUUID();
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.entryType = entryType;
        this.amountCents = amountCents;
    }

    /** Valor com sinal: DEBIT negativo, CREDIT positivo. */
    public long signedAmountCents() {
        return DEBIT.equals(entryType) ? -amountCents : amountCents;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getAccountId() { return accountId; }
    public String getEntryType() { return entryType; }
    public long getAmountCents() { return amountCents; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
