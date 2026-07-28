package com.apms.domain.admin.service;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.admin.dto.*;
import com.apms.domain.audit.AuditLog;
import com.apms.domain.audit.repository.sql.AuditLogRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.UserProfile;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final AccountRepository accountRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogRepository auditLogRepository;

    private final java.util.concurrent.ConcurrentHashMap<String, String> systemConfig = new java.util.concurrent.ConcurrentHashMap<>(java.util.Map.of(
            "ai_threshold", "75",
            "crawl_freq", "Every 6 hours",
            "approval_ttl", "48",
            "max_upload", "50",
            "lang", "Vietnamese",
            "timezone", "Asia/Ho_Chi_Minh (UTC+7)"
    ));

    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> securityConfig = new java.util.concurrent.ConcurrentHashMap<>(java.util.Map.of(
            "mfa", true,
            "session", true,
            "ip_lock", true,
            "pass_policy", false,
            "audit", true
    ));

    private final java.util.List<String> trustedIps = new java.util.concurrent.CopyOnWriteArrayList<>(java.util.List.of(
            "192.168.1.0/24", "10.0.0.0/8", "103.72.96.0/21"
    ));

    public SystemSettingsResponseDto getSystemSettings() {
        return SystemSettingsResponseDto.builder()
                .system(new java.util.HashMap<>(systemConfig))
                .security(new java.util.HashMap<>(securityConfig))
                .trustedIps(new java.util.ArrayList<>(trustedIps))
                .build();
    }

    public SystemSettingsResponseDto updateSystemSettings(SystemSettingsDto dto) {
        if (dto.getSystem() != null) {
            String aiThreshold = dto.getSystem().get("ai_threshold");
            if (aiThreshold != null) {
                try {
                    int val = Integer.parseInt(aiThreshold);
                    if (val < 0 || val > 100) {
                        throw new BusinessValidationException("AI Threshold must be between 0 and 100");
                    }
                } catch (NumberFormatException e) {
                    throw new BusinessValidationException("AI Threshold must be a valid integer");
                }
            }

            String approvalTtl = dto.getSystem().get("approval_ttl");
            if (approvalTtl != null) {
                try {
                    int val = Integer.parseInt(approvalTtl);
                    if (val <= 0) {
                        throw new BusinessValidationException("Approval TTL must be greater than 0");
                    }
                } catch (NumberFormatException e) {
                    throw new BusinessValidationException("Approval TTL must be a valid integer");
                }
            }

            String maxUpload = dto.getSystem().get("max_upload");
            if (maxUpload != null) {
                try {
                    int val = Integer.parseInt(maxUpload);
                    if (val <= 0) {
                        throw new BusinessValidationException("Max Upload size must be greater than 0");
                    }
                } catch (NumberFormatException e) {
                    throw new BusinessValidationException("Max Upload must be a valid integer");
                }
            }

            systemConfig.putAll(dto.getSystem());
        }
        if (dto.getSecurity() != null) {
            securityConfig.putAll(dto.getSecurity());
        }
        if (dto.getTrustedIps() != null) {
            String ipRegex = "^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(\\/([0-9]|[1-2][0-9]|3[0-2]))?$";
            for (String ip : dto.getTrustedIps()) {
                if (!ip.matches(ipRegex)) {
                    throw new BusinessValidationException("Invalid IP/CIDR format: " + ip);
                }
            }
            trustedIps.clear();
            trustedIps.addAll(dto.getTrustedIps());
        }
        return getSystemSettings();
    }

    @Transactional(readOnly = true)
    public Page<AccountAdminResponse> getAccounts(Pageable pageable) {
        return accountRepository.findAll(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<AccountAdminResponse> searchAccountsByEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return accountRepository.findAll().stream().map(this::toResponse).collect(Collectors.toList());
        }
        return accountRepository.findByEmailContainingIgnoreCase(email.trim())
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AccountAdminResponse getAccount(Long id) {
        return toResponse(findAccount(id));
    }

    @Transactional
    public AccountAdminResponse createAccount(CreateAccountRequest request) {
        validateUnique(request.getEmail(), request.getUsername(), null);

        NameParts nameParts = splitName(request.getName());
        SystemRole role = parseRole(request.getRole());

        Account account = Account.builder()
                .email(request.getEmail().trim().toLowerCase(Locale.ROOT))
                .username(request.getUsername().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .isActive(true)
                .roles(Set.of(role))
                .build();
        account = accountRepository.save(account);

        UserProfile profile = UserProfile.builder()
                .account(account)
                .email(account.getEmail())
                .passwordHash(account.getPasswordHash())
                .firstName(nameParts.firstName)
                .lastName(nameParts.lastName)
                .build();
        userProfileRepository.save(profile);

        return toResponse(account);
    }

    @Transactional
    public AccountAdminResponse updateAccount(Long id, UpdateAccountRequest request) {
        Account account = findAccount(id);

        if (StringUtils.hasText(request.getEmail())) {
            String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
            if (!email.equalsIgnoreCase(account.getEmail()) && accountRepository.existsByEmail(email)) {
                throw new BusinessValidationException("Email already exists: " + email);
            }
            account.setEmail(email);
        }

        if (StringUtils.hasText(request.getUsername())) {
            String username = request.getUsername().trim();
            if (!username.equalsIgnoreCase(account.getUsername()) && accountRepository.existsByUsername(username)) {
                throw new BusinessValidationException("Username already exists: " + username);
            }
            account.setUsername(username);
        }

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            account.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }

        if (StringUtils.hasText(request.getRole())) {
            account.setRoles(Set.of(parseRole(request.getRole())));
        }

        if (request.getActive() != null) {
            account.setIsActive(request.getActive());
        }

        account = accountRepository.save(account);
        return toResponse(account);
    }

    @Transactional
    public AccountAdminResponse toggleAccountStatus(Long id) {
        Account account = findAccount(id);
        account.setIsActive(!Boolean.TRUE.equals(account.getIsActive()));
        return toResponse(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public List<RoleSummaryDto> getRoles() {
        long ownerCount = countByRole(SystemRole.BUSINESS_OWNER);
        long directorCount = countByRole(SystemRole.BUSINESS_DIRECTOR);
        long managerCount = countByRole(SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        long keyMemberCount = countByRole(SystemRole.KEY_MEMBER);
        long staffCount = countByRole(SystemRole.RESEARCH_STAFF);

        return List.of(
                RoleSummaryDto.builder()
                        .id("ROLE_BUSINESS_OWNER")
                        .key(SystemRole.BUSINESS_OWNER.name())
                        .name("Business Owner")
                        .displayName("Business Owner")
                        .description("Full access to strategic and administrative areas.")
                        .userCount(ownerCount)
                        .permissionCount(26)
                        .build(),
                RoleSummaryDto.builder()
                        .id("ROLE_BUSINESS_DIRECTOR")
                        .key(SystemRole.BUSINESS_DIRECTOR.name())
                        .name("Business Director")
                        .displayName("Business Director")
                        .description("Leads strategy, ecosystem intelligence and executive review.")
                        .userCount(directorCount)
                        .permissionCount(20)
                        .build(),
                RoleSummaryDto.builder()
                        .id("ROLE_BUSINESS_DEVELOPMENT_MANAGER")
                        .key(SystemRole.BUSINESS_DEVELOPMENT_MANAGER.name())
                        .name("Business Development Manager")
                        .displayName("Business Development Manager")
                        .description("Manages projects, approvals and operational flows.")
                        .userCount(managerCount)
                        .permissionCount(18)
                        .build(),
                RoleSummaryDto.builder()
                        .id("ROLE_KEY_MEMBER")
                        .key(SystemRole.KEY_MEMBER.name())
                        .name("Key Member")
                        .displayName("Key Member")
                        .description("Reviews extracted data, validates candidates and supports onboarding.")
                        .userCount(keyMemberCount)
                        .permissionCount(16)
                        .build(),
                RoleSummaryDto.builder()
                        .id("ROLE_RESEARCH_STAFF")
                        .key(SystemRole.RESEARCH_STAFF.name())
                        .name("Research Staff")
                        .displayName("Research Staff")
                        .description("Handles data collection, extraction and review.")
                        .userCount(staffCount)
                        .permissionCount(14)
                        .build()
        );
    }

    @Transactional(readOnly = true)
    public List<PermissionDto> getPermissions() {
        return List.of(
                perm("users.create", "Users", "Create users", true, false, false, false, false),
                perm("users.read", "Users", "Read users", true, false, false, false, false),
                perm("users.update", "Users", "Update users", true, false, false, false, false),
                perm("users.deactivate", "Users", "Deactivate users", true, false, false, false, false),
                perm("projects.create", "Projects", "Create projects", true, true, false, false, false),
                perm("projects.read", "Projects", "Read projects", true, true, true, false, false),
                perm("projects.update", "Projects", "Update projects", true, true, false, false, false),
                perm("documents.upload", "Documents", "Upload documents", true, true, true, false, false),
                perm("documents.review", "Documents", "Review documents", true, true, true, false, false),
                perm("candidates.review", "Candidates", "Review candidates", true, true, true, false, false),
                perm("profiles.read", "Profiles", "Read company profiles", true, true, true, true, true),
                perm("graphs.read", "Graph", "Read network graph", true, true, false, false, false),
                perm("audit.read", "Audit", "Read audit logs", true, false, false, false, false),
                perm("settings.read", "Settings", "Read system settings", true, false, false, false, false)
        );
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getAuditLogs(Pageable pageable) {
        return auditLogRepository.findAll(pageable).map(this::toResponse);
    }

    private void validateUnique(String email, String username, Long ignoreId) {
        if (accountRepository.existsByEmail(email.trim().toLowerCase(Locale.ROOT))) {
            throw new BusinessValidationException("Email already exists: " + email);
        }
        if (accountRepository.existsByUsername(username.trim())) {
            throw new BusinessValidationException("Username already exists: " + username);
        }
    }

    private Account findAccount(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with id: " + id));
    }

    private long countByRole(SystemRole role) {
        return accountRepository.countByRolesContainingAndIsActiveTrue(role);
    }

    private SystemRole parseRole(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessValidationException("Role is required");
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ROLE_")) {
            normalized = normalized.substring("ROLE_".length());
        }

        normalized = switch (normalized) {
            case "ADMIN" -> SystemRole.BUSINESS_OWNER.name();
            case "DIRECTOR" -> SystemRole.BUSINESS_DIRECTOR.name();
            case "MANAGER" -> SystemRole.BUSINESS_DEVELOPMENT_MANAGER.name();
            case "STAFF" -> SystemRole.RESEARCH_STAFF.name();
            default -> normalized;
        };

        try {
            return SystemRole.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BusinessValidationException("Invalid role: " + value);
        }
    }

    private PermissionDto perm(String id, String module, String action, boolean admin, boolean director, boolean manager, boolean keymember, boolean staff) {
        return PermissionDto.builder()
                .id(id)
                .module(module)
                .action(action)
                .admin(admin)
                .director(director)
                .manager(manager)
                .keymember(keymember)
                .staff(staff)
                .build();
    }

    private AuditLogResponse toResponse(AuditLog auditLog) {
        return AuditLogResponse.builder()
                .id(auditLog.getId())
                .actorAccountId(auditLog.getActorAccountId())
                .actorEmail(auditLog.getActorAccount() != null ? auditLog.getActorAccount().getEmail() : null)
                .projectId(auditLog.getProjectId())
                .action(auditLog.getAction())
                .entityType(auditLog.getEntityType())
                .entityId(auditLog.getEntityId())
                .detail(auditLog.getDetail())
                .timestamp(auditLog.getTimestamp())
                .build();
    }

    private AccountAdminResponse toResponse(Account account) {
        UserProfile profile = userProfileRepository.findByAccountId(account.getId()).orElse(null);
        String firstName = profile != null ? profile.getFirstName() : null;
        String lastName = profile != null ? profile.getLastName() : null;
        String name = java.util.stream.Stream.of(firstName, lastName)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" "))
                .trim();

        Set<SystemRole> roles = account.getRoles();
        SystemRole primaryRole = roles != null && !roles.isEmpty() ? roles.iterator().next() : SystemRole.RESEARCH_STAFF;

        return AccountAdminResponse.builder()
                .id(account.getId())
                .email(account.getEmail())
                .username(account.getUsername() != null ? account.getUsername() : account.getEmail().split("@")[0])
                .name(StringUtils.hasText(name) ? name : account.getEmail())
                .firstName(firstName)
                .lastName(lastName)
                .role("ROLE_" + primaryRole.name())
                .roleName(toDisplayRoleName(primaryRole))
                .roles(roles)
                .active(Boolean.TRUE.equals(account.getIsActive()))
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    private String toDisplayRoleName(SystemRole role) {
        return switch (role) {
            case SYSTEM_ADMIN -> "System Administrator";
            case BUSINESS_OWNER -> "Business Owner";
            case BUSINESS_DIRECTOR -> "Business Director";
            case BUSINESS_DEVELOPMENT_MANAGER -> "Business Development Manager";
            case BUSINESS_DEVELOPMENT_STAFF -> "Business Development Staff";
            case KEY_MEMBER -> "Key Member";
            case RESEARCH_STAFF -> "Research Staff";
        };
    }

    private NameParts splitName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (!StringUtils.hasText(trimmed)) {
            return new NameParts("", "");
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 1) {
            return new NameParts(parts[0], "");
        }
        String firstName = parts[0];
        String lastName = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length));
        return new NameParts(firstName, lastName);
    }

    private record NameParts(String firstName, String lastName) {}
}
