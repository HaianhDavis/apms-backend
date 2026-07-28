package com.apms.domain.user.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.user.Account;
import com.apms.domain.user.UserProfile;
import com.apms.domain.user.dto.*;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final AccountRepository accountRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUserProfile(Long currentUserId) {
        return getProfileResponse(currentUserId);
    }

    @Transactional
    public UserProfileResponse createUser(CreateUserRequest request, Long adminId) {
        if (accountRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + request.getEmail());
        }

        Account account = Account.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .isActive(request.getEnabled() != null ? request.getEnabled() : true)
                .roles(request.getRoles())
                .build();
        account = accountRepository.save(account);

        UserProfile profile = UserProfile.builder()
                .account(account)
                .firstName(request.getFullName()) // Simplified mapping for MVP
                .lastName("")
                .build();
        userProfileRepository.save(profile);

        auditLogService.log(adminId, AuditAction.USER_CREATED, "Account", account.getId().toString(), "Created user: " + account.getEmail());

        return mapToResponse(account, profile);
    }

    @Transactional
    public UserProfileResponse updateUser(Long targetUserId, UpdateUserRequest request, Long adminId) {
        Account account = accountRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!account.getEmail().equals(request.getEmail()) && accountRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + request.getEmail());
        }

        account.setEmail(request.getEmail());
        accountRepository.save(account);

        UserProfile profile = userProfileRepository.findByAccountId(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found"));

        profile.setFirstName(request.getFullName());
        profile.setLastName("");
        userProfileRepository.save(profile);

        auditLogService.log(adminId, AuditAction.USER_UPDATED, "Account", account.getId().toString(), "Updated user details: " + account.getEmail());

        return mapToResponse(account, profile);
    }

    @Transactional
    public void updateUserStatus(Long targetUserId, UpdateUserStatusRequest request, Long adminId) {
        Account targetAccount = accountRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        boolean isDisabling = Boolean.FALSE.equals(request.getEnabled());

        if (isDisabling) {
            // Rule 2.1: Self lock prevention
            if (adminId != null && adminId.equals(targetUserId)) {
                throw new com.apms.common.exception.BusinessValidationException("Không thể tự khóa tài khoản của chính mình");
            }

            Account actorAccount = adminId != null ? accountRepository.findById(adminId).orElse(null) : null;
            if (actorAccount != null) {
                int actorRank = getMaxRoleRank(actorAccount.getRoles());
                int targetRank = getMaxRoleRank(targetAccount.getRoles());

                // Rule 2.2: Owner locking Admin prevention
                if (actorRank == 5 && targetRank == 6) {
                    throw new org.springframework.security.access.AccessDeniedException("Tài khoản Owner không có quyền khóa tài khoản Admin");
                }
            }

            // Rule 2.3: Last active Admin protection
            if (targetAccount.getRoles() != null && targetAccount.getRoles().contains(SystemRole.SYSTEM_ADMIN) && Boolean.TRUE.equals(targetAccount.getIsActive())) {
                long activeAdminCount = accountRepository.countByRolesContainingAndIsActiveTrue(SystemRole.SYSTEM_ADMIN);
                if (activeAdminCount <= 1) {
                    throw new com.apms.common.exception.BusinessValidationException("Không thể khóa tài khoản Admin cuối cùng còn hoạt động trong hệ thống");
                }
            }
        }

        targetAccount.setIsActive(request.getEnabled());
        accountRepository.save(targetAccount);

        auditLogService.log(adminId, AuditAction.USER_STATUS_CHANGED, "Account", targetAccount.getId().toString(), "Changed status to: " + request.getEnabled());
    }

    @Transactional
    public void assignUserRoles(Long targetUserId, AssignUserRolesRequest request, Long adminId) {
        Account targetAccount = accountRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Account actorAccount = adminId != null ? accountRepository.findById(adminId).orElse(null) : null;
        if (actorAccount != null) {
            int actorMaxRank = getMaxRoleRank(actorAccount.getRoles());
            int requestedMaxRank = getMaxRoleRank(request.getRoles());

            // Rule 1: Role Hierarchy Check (SYSTEM_ADMIN rank 6 can assign any role)
            if (actorMaxRank < 6 && requestedMaxRank >= actorMaxRank) {
                throw new org.springframework.security.access.AccessDeniedException("Bạn không có quyền gán role cao hơn hoặc ngang cấp mình");
            }
        }

        targetAccount.setRoles(new java.util.HashSet<>(request.getRoles()));
        accountRepository.save(targetAccount);

        auditLogService.log(adminId, AuditAction.USER_ROLES_UPDATED, "Account", targetAccount.getId().toString(), "Updated roles for: " + targetAccount.getEmail());
    }

    public static int getRoleRank(SystemRole role) {
        if (role == null) return 0;
        return switch (role) {
            case SYSTEM_ADMIN -> 6;
            case BUSINESS_OWNER -> 5;
            case BUSINESS_DIRECTOR -> 4;
            case BUSINESS_DEVELOPMENT_MANAGER -> 3;
            case KEY_MEMBER -> 2;
            case BUSINESS_DEVELOPMENT_STAFF, RESEARCH_STAFF -> 1;
        };
    }

    public static int getMaxRoleRank(java.util.Set<SystemRole> roles) {
        if (roles == null || roles.isEmpty()) return 0;
        return roles.stream().mapToInt(UserService::getRoleRank).max().orElse(0);
    }

    public List<SystemRole> getAllRoles() {
        return Arrays.stream(SystemRole.values())
                .filter(role -> role != SystemRole.RESEARCH_STAFF) // hide deprecated role
                .collect(Collectors.toList());
    }

    private UserProfileResponse getProfileResponse(Long userId) {
        Account account = accountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        UserProfile profile = userProfileRepository.findByAccountId(userId).orElse(null);

        return mapToResponse(account, profile);
    }

    @Transactional
    public UserProfileResponse updateSelfProfile(Long accountId, UpdateUserRequest request) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!account.getEmail().equals(request.getEmail()) && accountRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + request.getEmail());
        }

        account.setEmail(request.getEmail());
        accountRepository.save(account);

        UserProfile profile = userProfileRepository.findByAccountId(accountId)
                .orElse(new UserProfile());

        profile.setAccount(account);
        profile.setFirstName(request.getFullName());
        profile.setLastName("");
        userProfileRepository.save(profile);

        auditLogService.log(accountId, AuditAction.USER_UPDATED, "Account", account.getId().toString(), "Self-updated profile: " + account.getEmail());

        return mapToResponse(account, profile);
    }

    private UserProfileResponse mapToResponse(Account account, UserProfile profile) {
        String fullName = "";
        if (profile != null) {
            String first = profile.getFirstName() != null ? profile.getFirstName() : "";
            String last = profile.getLastName() != null ? profile.getLastName() : "";
            fullName = (first + " " + last).trim();
        }
        return UserProfileResponse.builder()
                .id(account.getId())
                .email(account.getEmail())
                .fullName(fullName)
                .roles(account.getRoles())
                .enabled(account.getIsActive())
                .createdAt(account.getCreatedAt())
                .build();
    }
}
