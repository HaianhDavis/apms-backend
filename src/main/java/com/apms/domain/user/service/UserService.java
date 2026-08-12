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

    @Transactional(readOnly = true)
    public List<UserProfileResponse> getAllUsers() {
        List<Account> accounts = accountRepository.findAllByDeletedAtIsNull();
        return accounts.stream()
                .map(account -> {
                    UserProfile profile = userProfileRepository.findByAccountId(account.getId())
                            .orElse(UserProfile.builder().firstName("").lastName("").build());
                    return mapToResponse(account, profile);
                })
                .collect(Collectors.toList());
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
        if (targetUserId.equals(adminId) && !request.getEnabled()) {
            throw new IllegalArgumentException("Cannot disable your own account");
        }

        Account account = accountRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        account.setIsActive(request.getEnabled());
        accountRepository.save(account);

        auditLogService.log(adminId, AuditAction.USER_STATUS_CHANGED, "Account", account.getId().toString(), "Changed status to: " + request.getEnabled());
    }

    @Transactional
    public void assignUserRoles(Long targetUserId, AssignUserRolesRequest request, Long adminId) {
        Account account = accountRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        account.setRoles(request.getRoles());
        accountRepository.save(account);

        auditLogService.log(adminId, AuditAction.USER_ROLES_UPDATED, "Account", account.getId().toString(), "Updated roles for: " + account.getEmail());
    }

    @Transactional
    public void resetUserPassword(Long targetUserId, String newPassword, Long adminId) {
        // Prevent admin from being locked out of their own account inadvertently
        Account account = accountRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + targetUserId));

        // Hash with BCrypt — never store or log plain text
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        accountRepository.save(account);

        // Audit: log actor and target, but NEVER the password itself
        auditLogService.log(
                adminId,
                AuditAction.ADMIN_RESET_USER_PASSWORD,
                "Account",
                account.getId().toString(),
                "Admin reset password for user: " + account.getEmail()
        );
    }

    @Transactional
    public void softDeleteUser(Long targetUserId, Long adminId) {
        if (targetUserId.equals(adminId)) {
            throw new IllegalArgumentException("Cannot delete your own account");
        }

        Account account = accountRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Protect the last SYSTEM_ADMIN
        boolean isSystemAdmin = account.getRoles().contains(SystemRole.SYSTEM_ADMIN);
        if (isSystemAdmin) {
            long adminCount = accountRepository.findAll().stream()
                    .filter(a -> a.getDeletedAt() == null && a.getRoles().contains(SystemRole.SYSTEM_ADMIN))
                    .count();
            if (adminCount <= 1) {
                throw new IllegalArgumentException("Cannot delete the last SYSTEM_ADMIN account");
            }
        }

        account.setIsActive(false);
        account.setDeletedAt(java.time.LocalDateTime.now());
        accountRepository.save(account);

        auditLogService.log(adminId, AuditAction.USER_DELETED, "Account", account.getId().toString(),
                "Soft-deleted user: " + account.getEmail());
    }


    public List<SystemRole> getAllRoles() {
        return Arrays.stream(SystemRole.values())
                .filter(role -> role != SystemRole.RESEARCH_STAFF) // hide deprecated role
                .collect(Collectors.toList());
    }

    private UserProfileResponse getProfileResponse(Long userId) {
        Account account = accountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        UserProfile profile = userProfileRepository.findByAccountId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found"));

        return mapToResponse(account, profile);
    }

    private UserProfileResponse mapToResponse(Account account, UserProfile profile) {
        return UserProfileResponse.builder()
                .id(account.getId())
                .email(account.getEmail())
                .fullName((profile.getFirstName() + " " + profile.getLastName()).trim())
                .roles(account.getRoles())
                .enabled(account.getIsActive())
                .createdAt(account.getCreatedAt())
                .build();
    }
}
