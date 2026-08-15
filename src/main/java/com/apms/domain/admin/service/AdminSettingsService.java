//package com.apms.domain.admin.service;
//
//import com.apms.common.enums.AuditAction;
//import com.apms.domain.admin.IpWhitelistEntry;
//import com.apms.domain.admin.SystemSetting;
//import com.apms.domain.admin.dto.IpWhitelistEntryDto;
//import com.apms.domain.admin.dto.IpWhitelistEntryRequest;
//import com.apms.domain.admin.dto.IpWhitelistResponse;
//import com.apms.domain.admin.dto.SystemSettingsResponse;
//import com.apms.domain.admin.repository.sql.IpWhitelistRepository;
//import com.apms.domain.admin.repository.sql.SystemSettingRepository;
//import com.apms.domain.audit.service.AuditLogService;
//import lombok.RequiredArgsConstructor;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.util.List;
//import java.util.Map;
//import java.util.stream.Collectors;
//
//@Service
//@RequiredArgsConstructor
//public class AdminSettingsService {
//
//    private static final String KEY_IP_WHITELIST_ENABLED = "ip_whitelist_enabled";
//
//    private static final Map<String, String> DEFAULT_SETTINGS = Map.of(
//            "ai_threshold", "75",
//            "crawl_freq", "Every 6 hours",
//            "approval_ttl", "48",
//            "max_upload", "50",
//            "lang", "Vietnamese",
//            "timezone", "Asia/Ho_Chi_Minh (UTC+7)",
//            "data_sources", ""
//    );
//
//    private final SystemSettingRepository systemSettingRepository;
//    private final IpWhitelistRepository ipWhitelistRepository;
//    private final AuditLogService auditLogService;
//
//    @Transactional(readOnly = true)
//    public SystemSettingsResponse getSettings() {
//        Map<String, String> stored = systemSettingRepository.findAll().stream()
//                .filter(setting -> !KEY_IP_WHITELIST_ENABLED.equals(setting.getKey()))
//                .collect(Collectors.toMap(SystemSetting::getKey, SystemSetting::getValue));
//
//        DEFAULT_SETTINGS.forEach(stored::putIfAbsent);
//
//        return SystemSettingsResponse.builder()
//                .settings(stored)
//                .ipWhitelistEnabled(isIpWhitelistEnabled())
//                .build();
//    }
//
//    @Transactional
//    public void updateSettings(Map<String, String> settings, Long adminId) {
//        settings.entrySet().stream()
//                .filter(entry -> !KEY_IP_WHITELIST_ENABLED.equals(entry.getKey()))
//                .forEach(entry -> saveSetting(entry.getKey(), entry.getValue()));
//
//        auditLogService.log(adminId, AuditAction.SYSTEM_SETTINGS_UPDATED, "SystemSettings", null,
//                "Updated settings keys: " + String.join(", ", settings.keySet()));
//    }
//
//    @Transactional(readOnly = true)
//    public IpWhitelistResponse getIpWhitelist() {
//        List<IpWhitelistEntryDto> entries = ipWhitelistRepository.findAllByOrderByIdAsc().stream()
//                .map(this::toDto)
//                .collect(Collectors.toList());
//
//        return IpWhitelistResponse.builder()
//                .entries(entries)
//                .enabled(isIpWhitelistEnabled())
//                .build();
//    }
//
//    @Transactional
//    public void setIpWhitelistEnabled(Boolean enabled, Long adminId) {
//        saveSetting(KEY_IP_WHITELIST_ENABLED, String.valueOf(enabled));
//        auditLogService.log(adminId, AuditAction.IP_WHITELIST_STATUS_CHANGED, "IpWhitelist", null,
//                "IP whitelist enforcement " + (Boolean.TRUE.equals(enabled) ? "enabled" : "disabled"));
//    }
//
//    @Transactional
//    public IpWhitelistEntryDto addIpEntry(IpWhitelistEntryRequest request, Long adminId) {
//        if (ipWhitelistRepository.findByIpAddress(request.getIpAddress()).isPresent()) {
//            throw new IllegalArgumentException("IP address already exists in the whitelist");
//        }
//
//        IpWhitelistEntry entry = IpWhitelistEntry.builder()
//                .ipAddress(request.getIpAddress())
//                .description(request.getDescription())
//                .enabled(request.getEnabled())
//                .build();
//        IpWhitelistEntry saved = ipWhitelistRepository.save(entry);
//
//        auditLogService.log(adminId, AuditAction.IP_WHITELIST_ENTRY_ADDED, "IpWhitelist",
//                saved.getId().toString(), "Added IP rule: " + request.getIpAddress());
//
//        return toDto(saved);
//    }
//
//    @Transactional
//    public IpWhitelistEntryDto updateIpEntry(Long id, IpWhitelistEntryRequest request, Long adminId) {
//        IpWhitelistEntry entry = ipWhitelistRepository.findById(id)
//                .orElseThrow(() -> new IllegalArgumentException("IP whitelist entry not found"));
//
//        ipWhitelistRepository.findByIpAddress(request.getIpAddress())
//                .filter(existing -> !existing.getId().equals(id))
//                .ifPresent(existing -> {
//                    throw new IllegalArgumentException("IP address already exists in the whitelist");
//                });
//
//        entry.setIpAddress(request.getIpAddress());
//        entry.setDescription(request.getDescription());
//        entry.setEnabled(request.getEnabled());
//        IpWhitelistEntry saved = ipWhitelistRepository.save(entry);
//
//        auditLogService.log(adminId, AuditAction.IP_WHITELIST_ENTRY_UPDATED, "IpWhitelist",
//                saved.getId().toString(), "Updated IP rule: " + request.getIpAddress());
//
//        return toDto(saved);
//    }
//
//    @Transactional
//    public void deleteIpEntry(Long id, Long adminId) {
//        IpWhitelistEntry entry = ipWhitelistRepository.findById(id)
//                .orElseThrow(() -> new IllegalArgumentException("IP whitelist entry not found"));
//
//        ipWhitelistRepository.deleteById(id);
//        auditLogService.log(adminId, AuditAction.IP_WHITELIST_ENTRY_DELETED, "IpWhitelist",
//                id.toString(), "Removed IP rule: " + entry.getIpAddress());
//    }
//
//    @Transactional(readOnly = true)
//    public boolean isIpWhitelistEnabled() {
//        return systemSettingRepository.findById(KEY_IP_WHITELIST_ENABLED)
//                .map(setting -> Boolean.parseBoolean(setting.getValue()))
//                .orElse(false);
//    }
//
//    @Transactional(readOnly = true)
//    public List<IpWhitelistEntry> findEnabledIpEntries() {
//        return ipWhitelistRepository.findAllByEnabledTrue();
//    }
//
//    private void saveSetting(String key, String value) {
//        SystemSetting setting = systemSettingRepository.findById(key)
//                .map(existing -> {
//                    existing.setValue(value);
//                    return existing;
//                })
//                .orElseGet(() -> SystemSetting.builder().key(key).value(value).build());
//        systemSettingRepository.save(setting);
//    }
//
//    private IpWhitelistEntryDto toDto(IpWhitelistEntry entry) {
//        return IpWhitelistEntryDto.builder()
//                .id(entry.getId())
//                .ipAddress(entry.getIpAddress())
//                .description(entry.getDescription())
//                .enabled(entry.getEnabled())
//                .createdAt(entry.getCreatedAt())
//                .build();
//    }
//}
