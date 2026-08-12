package com.apms.domain.admin.service;

import com.apms.common.enums.AuditAction;
import com.apms.domain.admin.dto.AdminSettingsResponse;
import com.apms.domain.admin.entity.AdminSetting;
import com.apms.domain.admin.repository.AdminSettingRepository;
import com.apms.domain.audit.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminSettingService {

    private final AdminSettingRepository settingRepository;
    private final AdminIpWhitelistService ipWhitelistService;
    private final AuditLogService auditLogService;

    /**
     * Returns all admin settings as a key-value map plus IP whitelist feature state.
     */
    @Transactional(readOnly = true)
    public AdminSettingsResponse getAllSettings() {
        Map<String, Object> settingsMap = new LinkedHashMap<>();

        settingRepository.findAll().forEach(s -> {
            // Skip internal keys exposed separately
            if (!"ip_whitelist_enabled".equals(s.getSettingKey())) {
                settingsMap.put(s.getSettingKey(), parseValue(s));
            }
        });

        return AdminSettingsResponse.builder()
                .settings(settingsMap)
                .ipWhitelistEnabled(ipWhitelistService.isWhitelistEnabled())
                .build();
    }

    /**
     * Bulk upsert settings from a key-value map.
     * Each key is saved/updated atomically.
     */
    @Transactional
    public void saveSettings(Map<String, Object> newValues, Long adminId) {
        newValues.forEach((key, value) -> {
            // Skip internal system keys managed by dedicated services
            if ("ip_whitelist_enabled".equals(key)) return;

            AdminSetting setting = settingRepository.findBySettingKey(key)
                    .orElseGet(() -> AdminSetting.builder()
                            .settingKey(key)
                            .settingType(inferType(value))
                            .build());

            setting.setSettingValue(value != null ? String.valueOf(value) : null);
            setting.setUpdatedById(adminId);
            settingRepository.save(setting);
        });

        auditLogService.log(adminId, AuditAction.SYSTEM_SETTINGS_UPDATED,
                "AdminSetting", "bulk",
                "Updated " + newValues.size() + " settings");
    }

    /**
     * Get a single typed setting value, with a default fallback.
     */
    @Transactional(readOnly = true)
    public String getSetting(String key, String defaultValue) {
        return settingRepository.findBySettingKey(key)
                .map(AdminSetting::getSettingValue)
                .orElse(defaultValue);
    }

    private Object parseValue(AdminSetting s) {
        if (s.getSettingValue() == null) return null;
        return switch (s.getSettingType()) {
            case "BOOLEAN" -> Boolean.parseBoolean(s.getSettingValue());
            case "INTEGER" -> {
                try { yield Integer.parseInt(s.getSettingValue()); }
                catch (NumberFormatException e) { yield s.getSettingValue(); }
            }
            default -> s.getSettingValue();
        };
    }

    private String inferType(Object value) {
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Integer || value instanceof Long) return "INTEGER";
        return "STRING";
    }
}
