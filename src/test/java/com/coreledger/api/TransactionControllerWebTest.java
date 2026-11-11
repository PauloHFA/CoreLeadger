package com.coreledger.api;

import com.coreledger.domain.exception.InsufficientBalanceException;
import com.coreledger.domain.service.TransferOrchestrator;
import com.coreledger.infrastructure.security.ProblemDetailSecurityHandlers;
import com.coreledger.infrastructure.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@Import({SecurityConfiguration.class, ProblemDetailSecurityHandlers.class, GlobalExceptionHandler.class})
class TransactionControllerWebTest {

    @Autowired MockMvc mvc;
    @MockBean TransferOrchestrator orchestrator;
    @MockBean JwtDecoder jwtDecoder;

    private static String body() {
        return """
                {"sourceAccountId":"%s","targetAccountId":"%s","amountCents":15000}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void withoutTokenReturns401AndDoesNotTouchDomain() throws Exception {
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void readOnlyScopeReturns403AndDoesNotTouchDomain() throws Exception {
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "k")
                        .with(jwt().authorities(() -> "SCOPE_ledger:read"))
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void invalidPayloadReturns400() throws Exception {
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "k")
                        .with(jwt().authorities(() -> "SCOPE_payment:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validRequestReturns201() throws Exception {
        UUID txId = UUID.randomUUID();
        when(orchestrator.transfer(anyString(), any(), any(), anyLong(), any(), any())).thenReturn(txId);
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "k")
                        .with(jwt().authorities(() -> "SCOPE_payment:write"))
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value(txId.toString()));
    }

    @Test
    void insufficientBalanceReturns422() throws Exception {
        when(orchestrator.transfer(anyString(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new InsufficientBalanceException("Saldo insuficiente para a operação."));
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "k")
                        .with(jwt().authorities(() -> "SCOPE_payment:write"))
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnprocessableEntity());
    }
}
