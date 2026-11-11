package com.coreledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @Schema(example = "Maria Silva") @NotBlank @Size(max = 150) String holderName,
        @Schema(example = "0001-9") @NotBlank @Size(max = 30) String accountNumber,
        @Schema(description = "Saldo inicial em centavos", example = "50000") @PositiveOrZero long initialBalanceCents) {
}
