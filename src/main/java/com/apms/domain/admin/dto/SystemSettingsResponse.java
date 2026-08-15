package com.apms.domain.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class SystemSettingsResponse {
    private Map<String, String> settings;
    private Boolean ipWhitelistEnabled;
}
