package com.apms.domain.assistant.service;

public class ClarificationRequiredException extends RuntimeException {
    public ClarificationRequiredException(String message) {
        super(message);
    }
}
