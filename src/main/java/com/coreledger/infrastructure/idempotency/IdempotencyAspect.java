package com.coreledger.infrastructure.idempotency;

import com.coreledger.domain.exception.BusinessRuleException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * Idempotência via Redis (SETNX + TTL).
 * Estados: IN_PROGRESS (409), COMPLETED|status|json (reenvio devolve a resposta original),
 * FAILED|mensagem (erro de negócio, reenvio devolve 422). Falhas transitórias liberam a chave.
 */
@Aspect
@Component
public class IdempotencyAspect {

    public static final String HEADER = "Idempotency-Key";
    static final String PREFIX = "coreledger:idemp:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyAspect(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(com.coreledger.infrastructure.idempotency.IdempotentEndpoint)")
    public Object checkIdempotency(ProceedingJoinPoint pjp) throws Throwable {
        String key = currentRequest().getHeader(HEADER);
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cabeçalho Idempotency-Key ausente.");
        }
        String redisKey = PREFIX + key;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, "IN_PROGRESS", TTL);
        if (Boolean.FALSE.equals(acquired)) {
            return replay(redisTemplate.opsForValue().get(redisKey));
        }
        try {
            Object response = pjp.proceed();
            redisTemplate.opsForValue().set(redisKey, serialize(response), TTL);
            return response;
        } catch (BusinessRuleException ex) {
            redisTemplate.opsForValue().set(redisKey, "FAILED|" + ex.getMessage(), TTL);
            throw ex;
        } catch (Throwable ex) {
            redisTemplate.delete(redisKey);
            throw ex;
        }
    }

    private Object replay(String state) throws Exception {
        if (state == null || "IN_PROGRESS".equals(state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transação já está em processamento.");
        }
        if (state.startsWith("FAILED|")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, state.substring("FAILED|".length()));
        }
        // COMPLETED|<status>|<json>
        String[] parts = state.split("\\|", 3);
        return ResponseEntity.status(Integer.parseInt(parts[1]))
                .header("Idempotent-Replayed", "true")
                .header("Content-Type", "application/json")
                .body(objectMapper.readTree(parts[2]));
    }

    private String serialize(Object response) throws Exception {
        if (response instanceof ResponseEntity<?> entity) {
            return "COMPLETED|" + entity.getStatusCode().value() + "|" + objectMapper.writeValueAsString(entity.getBody());
        }
        return "COMPLETED|200|" + objectMapper.writeValueAsString(response);
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.currentRequestAttributes();
        return ((ServletRequestAttributes) attrs).getRequest();
    }
}
