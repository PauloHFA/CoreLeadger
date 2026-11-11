package com.coreledger.api.dto;

import java.util.UUID;

public record TransferResponse(UUID transactionId, String status, long amountCents) {
}
