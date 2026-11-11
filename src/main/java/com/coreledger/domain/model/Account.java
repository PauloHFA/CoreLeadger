package com.coreledger.domain.model;

import com.coreledger.domain.exception.InsufficientBalanceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected Account() {
    }

    public Account(String holderName, String accountNumber) {
        this(UUID.randomUUID(), holderName, accountNumber, 0);
    }

    public Account(UUID id, String holderName, String accountNumber, long balanceCents) {
        this.id = id;
        this.holderName = holderName;
        this.accountNumber = accountNumber;
        this.balanceCents = balanceCents;
    }

    /** Debita o valor; nunca permite saldo negativo nem valores não positivos. */
    public void debit(long amountCents) {
        requirePositive(amountCents);
        if (balanceCents < amountCents) {
            throw new InsufficientBalanceException("Saldo insuficiente para a operação.");
        }
        balanceCents -= amountCents;
    }

    public void credit(long amountCents) {
        requirePositive(amountCents);
        balanceCents += amountCents;
    }

    private static void requirePositive(long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("O valor deve ser maior que zero.");
        }
    }

    public UUID getId() { return id; }
    public String getHolderName() { return holderName; }
    public String getAccountNumber() { return accountNumber; }
    public long getBalanceCents() { return balanceCents; }
    public Long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
