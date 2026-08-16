package com.apms.domain.admin.service;

import com.apms.common.enums.AuditAction;
import com.apms.domain.admin.dto.IpWhitelistResponse;
import com.apms.domain.admin.dto.IpWhitelistRequest;
import com.apms.domain.admin.entity.AdminIpWhitelist;
import com.apms.domain.admin.entity.AdminSetting;
import com.apms.domain.admin.repository.AdminIpWhitelistRepository;
import com.apms.domain.admin.repository.AdminSettingRepository;
import com.apms.domain.audit.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminIpWhitelistService {

    private static final String WHITELIST_ENABLED_KEY = "ip_whitelist_enabled";

    private final AdminIpWhitelistRepository ipWhitelistRepository;
    private final AdminSettingRepository adminSettingRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<IpWhitelistResponse> getAllEntries() {
        return ipWhitelistRepository.findAllByOrderByCreatedAtAsc()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public boolean isWhitelistEnabled() {
        return adminSettingRepository.findBySettingKey(WHITELIST_ENABLED_KEY)
                .map(s -> "true".equalsIgnoreCase(s.getSettingValue()))
                .orElse(false);
    }

    /**
     * Returns all enabled IP addresses for the security filter.
     */
    @Transactional(readOnly = true)
    public List<String> getEnabledIpAddresses() {
        return ipWhitelistRepository.findByEnabledTrue()
                .stream()
                .map(AdminIpWhitelist::getIpAddress)
                .collect(Collectors.toList());
    }

    @Transactional
    public IpWhitelistResponse addEntry(IpWhitelistRequest request, Long adminId) {
        if (ipWhitelistRepository.existsByIpAddress(request.getIpAddress())) {
            throw new IllegalArgumentException("IP address already exists in whitelist: " + request.getIpAddress());
        }

        AdminIpWhitelist entry = AdminIpWhitelist.builder()
                .ipAddress(request.getIpAddress())
                .description(request.getDescription())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .createdById(adminId)
                .build();

        entry = ipWhitelistRepository.save(entry);

        auditLogService.log(adminId, AuditAction.IP_WHITELIST_ENTRY_ADDED,
                "AdminIpWhitelist", entry.getId().toString(),
                "Added IP to whitelist: " + request.getIpAddress());

        return toResponse(entry);
    }

    @Transactional
    public IpWhitelistResponse updateEntry(Long id, IpWhitelistRequest request, Long adminId) {
        AdminIpWhitelist entry = ipWhitelistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("IP whitelist entry not found: " + id));

        // Check duplicate only if IP is changing
        if (!entry.getIpAddress().equals(request.getIpAddress())
                && ipWhitelistRepository.existsByIpAddress(request.getIpAddress())) {
            throw new IllegalArgumentException("IP address already exists: " + request.getIpAddress());
        }

        entry.setIpAddress(request.getIpAddress());
        entry.setDescription(request.getDescription());
        if (request.getEnabled() != null) {
            entry.setEnabled(request.getEnabled());
        }

        entry = ipWhitelistRepository.save(entry);

        auditLogService.log(adminId, AuditAction.IP_WHITELIST_ENTRY_UPDATED,
                "AdminIpWhitelist", entry.getId().toString(),
                "Updated IP whitelist entry: " + request.getIpAddress());

        return toResponse(entry);
    }

    @Transactional
    public void deleteEntry(Long id, Long adminId) {
        AdminIpWhitelist entry = ipWhitelistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("IP whitelist entry not found: " + id));

        String ipAddress = entry.getIpAddress();
        ipWhitelistRepository.delete(entry);

        auditLogService.log(adminId, AuditAction.IP_WHITELIST_ENTRY_DELETED,
                "AdminIpWhitelist", id.toString(),
                "Removed IP from whitelist: " + ipAddress);
    }

    @Transactional
    public void setWhitelistEnabled(boolean enabled, Long adminId) {
        AdminSetting setting = adminSettingRepository.findBySettingKey(WHITELIST_ENABLED_KEY)
                .orElseGet(() -> AdminSetting.builder()
                        .settingKey(WHITELIST_ENABLED_KEY)
                        .settingType("BOOLEAN")
                        .description("IP whitelist enforcement feature toggle")
                        .build());

        setting.setSettingValue(String.valueOf(enabled));
        setting.setUpdatedById(adminId);
        adminSettingRepository.save(setting);

        auditLogService.log(adminId, AuditAction.IP_WHITELIST_STATUS_CHANGED, "AdminSetting", WHITELIST_ENABLED_KEY,
                "IP whitelist enforcement " + (enabled ? "ENABLED" : "DISABLED"));
    }

    private IpWhitelistResponse toResponse(AdminIpWhitelist entity) {
        return IpWhitelistResponse.builder()
                .id(entity.getId())
                .ipAddress(entity.getIpAddress())
                .description(entity.getDescription())
                .enabled(entity.getEnabled())
                .createdById(entity.getCreatedById())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
