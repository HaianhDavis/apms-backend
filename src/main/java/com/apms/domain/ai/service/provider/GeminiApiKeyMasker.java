package com.apms.domain.ai.service.provider;

public final class GeminiApiKeyMasker {

    private GeminiApiKeyMasker() {}

    /**
     * Masks an API key so that only a prefix and suffix are visible, e.g. "AIzaSy****xP9Q".
     * Never returns the complete key.
     */
    public static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "EMPTY";
        }
        String trimmed = key.trim();
        int len = trimmed.length();
        if (len >= 12) {
            return trimmed.substring(0, 6) + "****" + trimmed.substring(len - 4);
        } else if (len >= 8) {
            return trimmed.substring(0, 3) + "****" + trimmed.substring(len - 2);
        } else if (len >= 4) {
            return trimmed.substring(0, 1) + "****" + trimmed.substring(len - 1);
        } else {
            return "****";
        }
    }
}
