package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;

public class GeminiAllKeysUnavailableException extends BusinessValidationException {

    public GeminiAllKeysUnavailableException() {
        super("GEMINI_SERVICE_UNAVAILABLE", "AI service is temporarily unavailable. Please try again later.");
    }
}
