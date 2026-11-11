package com.coreledger.api;

import com.coreledger.api.dto.TransferRequest;
import com.coreledger.api.dto.TransferResponse;
import com.coreledger.domain.service.TransferOrchestrator;
import com.coreledger.infrastructure.idempotency.IdempotentEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transações")
public class TransactionController {

    private final TransferOrchestrator orchestrator;

    public TransactionController(TransferOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    @IdempotentEndpoint
    @Operation(summary = "Transfere valores entre contas (partidas dobradas)",
            description = "Exige escopo payment:write e o cabeçalho Idempotency-Key.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transferência efetuada"),
            @ApiResponse(responseCode = "400", description = "Payload inválido ou Idempotency-Key ausente"),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido"),
            @ApiResponse(responseCode = "403", description = "Escopo insuficiente"),
            @ApiResponse(responseCode = "409", description = "Requisição com a mesma chave em processamento"),
            @ApiResponse(responseCode = "422", description = "Saldo insuficiente ou regra de negócio violada"),
            @ApiResponse(responseCode = "503", description = "Adquirente indisponível")
    })
    public ResponseEntity<TransferResponse> transfer(
            @Parameter(description = "Chave única da operação", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferOrchestrator.Type type = request.type() == null ? TransferOrchestrator.Type.INTERNAL : request.type();
        UUID id = orchestrator.transfer(idempotencyKey, request.sourceAccountId(), request.targetAccountId(),
                request.amountCents(), request.description(), type);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TransferResponse(id, "COMPLETED", request.amountCents()));
    }
}
