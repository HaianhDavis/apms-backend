package com.apms.domain.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class AdminSettingsResponse {
    /**
     * All settings as a flat key-value map.
     * Frontend renders based on known keys.
     */
    private Map<String, Object> settings;

    /**
     * IP whitelist feature toggle (not in settings table — stored separately).
     */
    private Boolean ipWhitelistEnabled;
}
