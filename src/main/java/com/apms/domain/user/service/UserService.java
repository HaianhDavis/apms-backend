package com.apms.domain.user.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.auth.service.LoginMfaService;
import com.apms.domain.auth.service.RefreshTokenService;
import com.apms.domain.security.entity.AccountTotpCredential;
import com.apms.domain.security.repository.AccountTotpCredentialRepository;
import com.apms.domain.security.service.StepUpAuthenticationService;
import com.apms.domain.security.service.TotpEnrollmentService;
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
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final AccountRepository accountRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final TotpEnrollmentService totpEnrollmentService;
    private final AccountTotpCredentialRepository credentialRepository;
    private final LoginMfaService loginMfaService;
    private final RefreshTokenService refreshTokenService;
    private final StepUpAuthenticationService stepUpAuthenticationService;

    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUserProfile(Long currentUserId) {
        return getProfileResponse(currentUserId);
    }

    @Transactional
    public UserProfileResponse createUser(CreateUserRequest request, Long adminId) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Password confirmation does not match");
        }
        if (request.getRoles().contains(SystemRole.RESEARCH_STAFF)) {
            throw new IllegalArgumentException("Deprecated role is not allowed");
        }
        if (accountRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + request.getEmail());
        }

        Account account = Account.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .isActive(request.getEnabled() != null ? request.getEnabled() : true)
                .emailVerified(false)
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

    @Transactional(readOnly = true)
    public List<UserProfileResponse> listUsers() {
        Set<Long> mfaEnrolledAccountIds = credentialRepository.findAll().stream()
                .filter(AccountTotpCredential::isEnabled)
                .map(AccountTotpCredential::getAccountId)
                .collect(Collectors.toSet());

        return accountRepository.findAll().stream()
                .map(account -> mapAccountToResponse(account, mfaEnrolledAccountIds.contains(account.getId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public void resetAuthenticator(Long targetUserId, Long adminId) {
        if (targetUserId.equals(adminId)) {
            throw new IllegalArgumentException("You cannot reset your own Authenticator from Account Management");
        }

        Account account = accountRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + targetUserId));

        // 1. Invalidate persisted TOTP credentials
        totpEnrollmentService.resetEnrollment(targetUserId);

        // 2. Invalidate active OTP challenges (login/enrollment) for this user
        loginMfaService.invalidateActiveChallengesForAccount(targetUserId);

        // 3. Invalidate owner/step-up secure sessions if any
        stepUpAuthenticationService.invalidateOwnerSecureSession(targetUserId);

        // 4. Revoke active refresh token / session
        refreshTokenService.revokeToken(targetUserId);

        // 5. Record audit log
        auditLogService.log(adminId, AuditAction.TOTP_CREDENTIAL_RESET, "Account", targetUserId.toString(),
                "Admin reset Authenticator for: " + account.getEmail());
    }

    @Transactional
    public void resetPassword(Long targetUserId, ResetPasswordRequest request, Long adminId) {
        Account account = accountRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        account.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        accountRepository.save(account);

        auditLogService.log(adminId, AuditAction.USER_PASSWORD_RESET, "Account", account.getId().toString(),
                "Reset password for: " + account.getEmail());
    }

    @Transactional
    public void updateUserStatus(Long targetUserId, UpdateUserStatusRequest request, Long adminId) {
        if (targetUserId.equals(adminId) && !request.getEnabled()) {
            throw new IllegalArgumentException("Cannot disable your own account");
        }

        Account account = accountRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        boolean activate = Boolean.TRUE.equals(request.getEnabled());
        account.setIsActive(activate);
        accountRepository.save(account);

        AuditAction action = activate ? AuditAction.ACTIVATE_USER : AuditAction.DEACTIVATE_USER;
        auditLogService.log(adminId, action, "Account", account.getId().toString(),
                (activate ? "Activated user: " : "Deactivated user: ") + account.getEmail());
    }

    @Transactional
    public void assignUserRoles(Long targetUserId, AssignUserRolesRequest request, Long adminId) {
        Account account = accountRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        account.setRoles(request.getRoles());
        accountRepository.save(account);

        auditLogService.log(adminId, AuditAction.USER_ROLES_UPDATED, "Account", account.getId().toString(), "Updated roles for: " + account.getEmail());
    }

    public List<SystemRole> getAllRoles() {
        return Arrays.stream(SystemRole.values())
                .filter(role -> role != SystemRole.RESEARCH_STAFF) // hide deprecated role
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<UserProfileResponse> searchActiveUsers(String email, SystemRole role) {
        String term = email == null ? "" : email.trim().toLowerCase();
        List<Account> accounts;
        if (role != null) {
            accounts = accountRepository.findActiveAccountsByRole(role);
        } else {
            accounts = accountRepository.findTop10ByEmailContainingIgnoreCaseAndIsActiveTrue(term);
        }

        return accounts.stream()
                .filter(a -> {
                    if (role == SystemRole.BUSINESS_DEVELOPMENT_STAFF) {
                        return a.getRoles() != null &&
                                a.getRoles().contains(SystemRole.BUSINESS_DEVELOPMENT_STAFF) &&
                                !a.getRoles().contains(SystemRole.SYSTEM_ADMIN) &&
                                !a.getRoles().contains(SystemRole.BUSINESS_OWNER) &&
                                !a.getRoles().contains(SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
                    }
                    return true;
                })
                .map(this::mapAccountToResponse)
                .filter(res -> {
                    if (term.isEmpty()) return true;
                    String emailMatch = res.getEmail() != null ? res.getEmail().toLowerCase() : "";
                    String nameMatch = res.getFullName() != null ? res.getFullName().toLowerCase() : "";
                    return emailMatch.contains(term) || nameMatch.contains(term);
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<UserProfileResponse> searchActiveUsersByEmail(String email) {
        return searchActiveUsers(email, null);
    }

    @Transactional
    public UserProfileResponse updateMyProfile(Long currentUserId, UpdateMyProfileRequest request) {
        Account account = accountRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        UserProfile profile = userProfileRepository.findByAccountId(currentUserId)
                .orElseGet(() -> {
                    UserProfile newProfile = UserProfile.builder()
                            .account(account)
                            .firstName(account.getEmail())
                            .lastName("")
                            .build();
                    return userProfileRepository.save(newProfile);
                });

        String trimmedName = request.getFullName() != null ? request.getFullName().trim() : "";
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("Full name cannot be blank");
        }

        profile.setFirstName(trimmedName);
        profile.setLastName("");
        userProfileRepository.save(profile);

        auditLogService.log(currentUserId, AuditAction.USER_UPDATED, "Account", account.getId().toString(), "Updated self profile name");

        return mapToResponse(account, profile);
    }

    private UserProfileResponse getProfileResponse(Long userId) {
        Account account = accountRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        UserProfile profile = userProfileRepository.findByAccountId(userId)
                .orElse(null);

        boolean mfaConfigured = credentialRepository.findByAccountId(userId)
                .map(AccountTotpCredential::isEnabled)
                .orElse(false);

        return mapToResponse(account, profile, mfaConfigured);
    }

    private UserProfileResponse mapToResponse(Account account, UserProfile profile) {
        boolean mfaConfigured = credentialRepository.findByAccountId(account.getId())
                .map(AccountTotpCredential::isEnabled)
                .orElse(false);
        return mapToResponse(account, profile, mfaConfigured);
    }

    private UserProfileResponse mapToResponse(Account account, UserProfile profile, Boolean authenticatorConfigured) {
        String dept = profile != null ? profile.getDepartment() : null;
        if (dept == null || dept.isBlank()) {
            boolean isAdmin = account.getRoles() != null && account.getRoles().stream()
                    .anyMatch(r -> r.name().contains("ADMIN"));
            dept = isAdmin ? "Platform Administration" : "Business Development";
        }

        String phone = profile != null ? profile.getPhone() : null;
        if ((phone == null || phone.isBlank()) && account.getPhoneNumber() != null && !account.getPhoneNumber().isBlank()) {
            phone = account.getPhoneNumber();
        }

        return UserProfileResponse.builder()
                .id(account.getId())
                .email(account.getEmail())
                .fullName(profile != null ? (profile.getFirstName() + " " + profile.getLastName()).trim() : account.getEmail())
                .roles(account.getRoles())
                .enabled(account.getIsActive())
                .emailVerified(account.getEmailVerified())
                .authenticatorConfigured(authenticatorConfigured)
                .createdAt(account.getCreatedAt())
                .phone(phone)
                .department(dept)
                .bio(profile != null ? profile.getBio() : null)
                .address(profile != null ? profile.getAddress() : null)
                .build();
    }

    private UserProfileResponse mapAccountToResponse(Account account) {
        boolean mfaConfigured = credentialRepository.findByAccountId(account.getId())
                .map(AccountTotpCredential::isEnabled)
                .orElse(false);
        return mapAccountToResponse(account, mfaConfigured);
    }

    private UserProfileResponse mapAccountToResponse(Account account, Boolean authenticatorConfigured) {
        UserProfile profile = userProfileRepository.findByAccountId(account.getId()).orElse(null);
        String fullName = profile != null
                ? (profile.getFirstName() + " " + profile.getLastName()).trim()
                : "";
        if (fullName.isBlank()) {
            fullName = account.getEmail();
        }

        String dept = profile != null ? profile.getDepartment() : null;
        if (dept == null || dept.isBlank()) {
            boolean isAdmin = account.getRoles() != null && account.getRoles().stream()
                    .anyMatch(r -> r.name().contains("ADMIN"));
            dept = isAdmin ? "Platform Administration" : "Business Development";
        }

        String phone = profile != null ? profile.getPhone() : null;
        if ((phone == null || phone.isBlank()) && account.getPhoneNumber() != null && !account.getPhoneNumber().isBlank()) {
            phone = account.getPhoneNumber();
        }

        return UserProfileResponse.builder()
                .id(account.getId())
                .email(account.getEmail())
                .fullName(fullName)
                .roles(account.getRoles())
                .enabled(account.getIsActive())
                .emailVerified(account.getEmailVerified())
                .authenticatorConfigured(authenticatorConfigured)
                .createdAt(account.getCreatedAt())
                .phone(phone)
                .department(dept)
                .bio(profile != null ? profile.getBio() : null)
                .address(profile != null ? profile.getAddress() : null)
                .build();
    }
}