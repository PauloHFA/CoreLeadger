package com.coreledger.api;

import com.coreledger.api.dto.IntegrityResponse;
import com.coreledger.api.dto.LedgerEntryResponse;
import com.coreledger.domain.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
@Tag(name = "Razão")
public class LedgerController {

    private final AccountService accountService;

    public LedgerController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/accounts/{accountId}/entries")
    @Operation(summary = "Extrato paginado do razão por período. Exige escopo ledger:read.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Extrato retornado com sucesso"),
            @ApiResponse(responseCode = "401", description = "Não autenticado"),
            @ApiResponse(responseCode = "403", description = "Escopo insuficiente"),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada")
    })
    public Page<LedgerEntryResponse> statement(
            @PathVariable UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        OffsetDateTime start = from != null ? from : OffsetDateTime.parse("1970-01-01T00:00:00Z");
        OffsetDateTime end = to != null ? to : OffsetDateTime.now().plusDays(1);
        return accountService.statement(accountId, start, end, PageRequest.of(page, Math.min(size, 200)))
                .map(LedgerEntryResponse::from);
    }

    @GetMapping("/accounts/{accountId}/integrity")
    @Operation(summary = "Compara o saldo consolidado com a soma histórica dos lançamentos. Exige escopo ledger:read.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verificação de integridade concluída"),
            @ApiResponse(responseCode = "401", description = "Não autenticado"),
            @ApiResponse(responseCode = "403", description = "Escopo insuficiente"),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada")
    })
    public IntegrityResponse integrity(@PathVariable UUID accountId) {
        long balance = accountService.get(accountId).getBalanceCents();
        long sum = accountService.ledgerSum(accountId);
        return new IntegrityResponse(accountId, balance, sum, balance == sum);
    }
}
