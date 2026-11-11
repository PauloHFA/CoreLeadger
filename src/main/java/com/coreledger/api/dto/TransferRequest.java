package com.coreledger.api.dto;

import com.coreledger.domain.service.TransferOrchestrator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Solicitação de transferência por partidas dobradas")
public record TransferRequest(
        @Schema(example = "0f8fad5b-d9cb-469f-a165-70867728950e") @NotNull UUID sourceAccountId,
        @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") @NotNull UUID targetAccountId,
        @Schema(description = "Valor em centavos", example = "15000") @Positive long amountCents,
        @Schema(description = "INTERNAL (padrão) ou EXTERNAL (autoriza na adquirente)", example = "INTERNAL")
        TransferOrchestrator.Type type,
        @Schema(example = "Pagamento de fatura") @Size(max = 255) String description) {
}
