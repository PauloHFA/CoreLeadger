package com.coreledger.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false)
    private String status;

    private String description;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected TransactionEntity() {
    }

    public TransactionEntity(String idempotencyKey, long amountCents, String status, String description, String traceId) {
        this.id = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.amountCents = amountCents;
        this.status = status;
        this.description = description;
        this.traceId = traceId;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public long getAmountCents() { return amountCents; }
    public String getStatus() { return status; }
    public String getDescription() { return description; }
    public String getTraceId() { return traceId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
