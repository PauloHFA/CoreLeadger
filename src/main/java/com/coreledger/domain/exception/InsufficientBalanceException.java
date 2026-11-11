package com.coreledger.domain.exception;

public class InsufficientBalanceException extends BusinessRuleException {
    public InsufficientBalanceException(String message) {
        super(message);
    }
}
