package com.coreledger.api.dto;

import java.util.UUID;

public record IntegrityResponse(UUID accountId, long balanceCents, long ledgerSumCents, boolean consistent) {
}
