package com.coreledger.api.dto;

import com.coreledger.domain.model.Account;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountResponse(UUID id, String holderName, String accountNumber, long balanceCents,
                              OffsetDateTime createdAt) {
    public static AccountResponse from(Account a) {
        return new AccountResponse(a.getId(), a.getHolderName(), a.getAccountNumber(), a.getBalanceCents(),
                a.getCreatedAt());
    }
}
