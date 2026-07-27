package com.apms.domain.score.outbox;

public class PayloadIntegrityException extends RuntimeException {
    public PayloadIntegrityException(String message) {
        super(message);
    }
}
