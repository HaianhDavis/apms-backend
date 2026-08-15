package com.apms.common.exception;

import lombok.Getter;

import java.util.Map;

@Getter
public class BusinessValidationException extends RuntimeException {

    private String errorCode;
    private Map<String, Object> details;

    public BusinessValidationException(String message) {
        super(message);
    }

    public BusinessValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessValidationException(String errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }
}
