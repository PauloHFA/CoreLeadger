package com.coreledger.infrastructure.idempotency;

import com.coreledger.domain.exception.BusinessRuleException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyAspectTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private IdempotencyAspect aspect;
    private ProceedingJoinPoint pjp;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        objectMapper = new ObjectMapper();
        aspect = new IdempotencyAspect(redisTemplate, objectMapper);
        pjp = mock(ProceedingJoinPoint.class);
    }

    private void mockRequest(String idempotencyKey) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (idempotencyKey != null) {
            request.addHeader(IdempotencyAspect.HEADER, idempotencyKey);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("Rejeita requisição sem cabeçalho Idempotency-Key com 400 Bad Request")
    void missingIdempotencyKeyThrows400() {
        mockRequest(null);
        assertThatThrownBy(() -> aspect.checkIdempotency(pjp))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("Primeira execução adquire lock IN_PROGRESS e grava COMPLETED ao concluir")
    void firstExecutionSucceeds() throws Throwable {
        mockRequest("K-123");
        String redisKey = IdempotencyAspect.PREFIX + "K-123";

        when(valueOperations.setIfAbsent(eq(redisKey), eq("IN_PROGRESS"), any(Duration.class))).thenReturn(true);
        when(pjp.proceed()).thenReturn(ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", "tx-1")));

        Object result = aspect.checkIdempotency(pjp);

        assertThat(result).isNotNull();
        verify(valueOperations).set(eq(redisKey), contains("COMPLETED|201"), any(Duration.class));
    }

    @Test
    @DisplayName("Requisição concorrente com chave IN_PROGRESS retorna 409 Conflict")
    void concurrentExecutionReturns409() {
        mockRequest("K-CONCURRENT");
        String redisKey = IdempotencyAspect.PREFIX + "K-CONCURRENT";

        when(valueOperations.setIfAbsent(eq(redisKey), eq("IN_PROGRESS"), any(Duration.class))).thenReturn(false);
        when(valueOperations.get(redisKey)).thenReturn("IN_PROGRESS");

        assertThatThrownBy(() -> aspect.checkIdempotency(pjp))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("Reenvio com chave COMPLETED retorna resposta original com header Idempotent-Replayed")
    void replayCompletedReturnsCachedResponse() throws Throwable {
        mockRequest("K-REPLAY");
        String redisKey = IdempotencyAspect.PREFIX + "K-REPLAY";

        when(valueOperations.setIfAbsent(eq(redisKey), eq("IN_PROGRESS"), any(Duration.class))).thenReturn(false);
        when(valueOperations.get(redisKey)).thenReturn("COMPLETED|201|{\"transactionId\":\"tx-99\"}");

        Object result = aspect.checkIdempotency(pjp);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("Reenvio de operação rejeitada por regra de negócio retorna 422")
    void replayFailedBusinessRuleReturns422() {
        mockRequest("K-FAIL");
        String redisKey = IdempotencyAspect.PREFIX + "K-FAIL";

        when(valueOperations.setIfAbsent(eq(redisKey), eq("IN_PROGRESS"), any(Duration.class))).thenReturn(false);
        when(valueOperations.get(redisKey)).thenReturn("FAILED|Saldo insuficiente para a operação.");

        assertThatThrownBy(() -> aspect.checkIdempotency(pjp))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    @DisplayName("Exceção transitória ou inesperada deleta a chave no Redis permitindo nova tentativa")
    void unexpectedExceptionClearsKey() throws Throwable {
        mockRequest("K-ERR");
        String redisKey = IdempotencyAspect.PREFIX + "K-ERR";

        when(valueOperations.setIfAbsent(eq(redisKey), eq("IN_PROGRESS"), any(Duration.class))).thenReturn(true);
        when(pjp.proceed()).thenThrow(new RuntimeException("DB Connection Timeout"));

        assertThatThrownBy(() -> aspect.checkIdempotency(pjp)).isInstanceOf(RuntimeException.class);
        verify(redisTemplate).delete(redisKey);
    }

    @Test
    @DisplayName("Violação de regra de negócio grava FAILED no Redis")
    void businessRuleExceptionStoresFailed() throws Throwable {
        mockRequest("K-BIZ");
        String redisKey = IdempotencyAspect.PREFIX + "K-BIZ";

        when(valueOperations.setIfAbsent(eq(redisKey), eq("IN_PROGRESS"), any(Duration.class))).thenReturn(true);
        when(pjp.proceed()).thenThrow(new BusinessRuleException("Conta bloqueada"));

        assertThatThrownBy(() -> aspect.checkIdempotency(pjp)).isInstanceOf(BusinessRuleException.class);
        verify(valueOperations).set(eq(redisKey), eq("FAILED|Conta bloqueada"), any(Duration.class));
    }
}
