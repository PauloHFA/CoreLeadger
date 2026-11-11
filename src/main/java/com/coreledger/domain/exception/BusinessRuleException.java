package com.coreledger.domain.exception;

/** Violação de regra de negócio (HTTP 422). Não é retentável com a mesma chave de idempotência. */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
