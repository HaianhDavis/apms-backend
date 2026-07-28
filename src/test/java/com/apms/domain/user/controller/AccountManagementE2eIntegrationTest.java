package com.apms.domain.user.controller;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.admin.dto.AccountAdminResponse;
import com.apms.domain.admin.dto.CreateAccountRequest;
import com.apms.domain.admin.service.AdminUserService;
import com.apms.domain.user.Account;
import com.apms.domain.user.dto.AssignUserRolesRequest;
import com.apms.domain.user.dto.UpdateUserStatusRequest;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AccountManagementE2eIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private UserService userService;

    @Autowired
    private AccountRepository accountRepository;

    private Account adminAccount;
    private Account ownerAccount;
    private Account staffAccount;

    @BeforeEach
    void setUp() {
        adminAccount = accountRepository.findByEmail("admin@apms.com")
                .orElseGet(() -> accountRepository.save(Account.builder()
                        .email("admin@apms.com").username("admin").passwordHash("hash").isActive(true).roles(Set.of(SystemRole.SYSTEM_ADMIN)).build()));

        ownerAccount = accountRepository.findByEmail("owner@apms.com")
                .orElseGet(() -> accountRepository.save(Account.builder()
                        .email("owner@apms.com").username("owner").passwordHash("hash").isActive(true).roles(Set.of(SystemRole.BUSINESS_OWNER)).build()));

        staffAccount = accountRepository.findByEmail("staff@apms.com")
                .orElseGet(() -> accountRepository.save(Account.builder()
                        .email("staff@apms.com").username("staff").passwordHash("hash").isActive(true).roles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF)).build()));
    }

    @Test
    @DisplayName("E2E 1: Admin creates new account and confirms appearance in database")
    void e2e1_adminCreatesAccount_shouldExistInDatabase() {
        long timestamp = System.currentTimeMillis();
        CreateAccountRequest req = new CreateAccountRequest();
        req.setEmail("e2e_db_" + timestamp + "@apms.com");
        req.setUsername("e2e_db_" + timestamp);
        req.setPassword("123456");
        req.setName("E2E Test User");
        req.setRole("ROLE_KEY_MEMBER");

        AccountAdminResponse response = adminUserService.createAccount(req);

        assertNotNull(response.getId());
        assertEquals("e2e_db_" + timestamp + "@apms.com", response.getEmail());
        assertTrue(accountRepository.findById(response.getId()).isPresent());
    }

    @Test
    @DisplayName("E2E 2: Admin assigns Owner role to user -> User gains BUSINESS_OWNER role")
    void e2e2_adminAssignsOwnerRole_shouldUpdateUserRole() {
        AssignUserRolesRequest req = new AssignUserRolesRequest();
        req.setRoles(Set.of(SystemRole.BUSINESS_OWNER));

        userService.assignUserRoles(staffAccount.getId(), req, adminAccount.getId());

        Account updated = accountRepository.findById(staffAccount.getId()).orElseThrow();
        assertTrue(updated.getRoles().contains(SystemRole.BUSINESS_OWNER));
    }

    @Test
    @DisplayName("E2E 3: Owner role assignment options hierarchy check")
    void e2e3_ownerRoleOptionsCheck_shouldExcludeHighRanks() {
        AssignUserRolesRequest reqAdmin = new AssignUserRolesRequest();
        reqAdmin.setRoles(Set.of(SystemRole.SYSTEM_ADMIN));

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                userService.assignUserRoles(staffAccount.getId(), reqAdmin, ownerAccount.getId()));

        assertTrue(ex.getMessage().contains("Bạn không có quyền gán role cao hơn hoặc ngang cấp mình"));
    }

    @Test
    @DisplayName("E2E 4: Owner attempts to lock Admin -> Blocked with 403 and status unchanged")
    void e2e4_ownerLocksAdmin_shouldBeBlockedWithStatusUnchanged() {
        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setEnabled(false);

        assertThrows(AccessDeniedException.class, () ->
                userService.updateUserStatus(adminAccount.getId(), req, ownerAccount.getId()));

        Account reloadedAdmin = accountRepository.findById(adminAccount.getId()).orElseThrow();
        assertTrue(reloadedAdmin.getIsActive(), "Admin account status MUST remain active");
    }

    @Test
    @DisplayName("E2E 5: Self account lock check -> Blocked with BusinessValidationException")
    void e2e5_selfAccountLock_shouldBeBlocked() {
        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setEnabled(false);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                userService.updateUserStatus(adminAccount.getId(), req, adminAccount.getId()));

        assertEquals("Không thể tự khóa tài khoản của chính mình", ex.getMessage());
    }

    @Test
    @DisplayName("E2E 6: Admin locks and unlocks user -> Real-time status toggles")
    void e2e6_adminLockAndUnlock_shouldToggleStatusRealTime() {
        UpdateUserStatusRequest lockReq = new UpdateUserStatusRequest();
        lockReq.setEnabled(false);
        userService.updateUserStatus(staffAccount.getId(), lockReq, adminAccount.getId());

        Account locked = accountRepository.findById(staffAccount.getId()).orElseThrow();
        assertFalse(locked.getIsActive(), "Account should be locked");

        UpdateUserStatusRequest unlockReq = new UpdateUserStatusRequest();
        unlockReq.setEnabled(true);
        userService.updateUserStatus(staffAccount.getId(), unlockReq, adminAccount.getId());

        Account unlocked = accountRepository.findById(staffAccount.getId()).orElseThrow();
        assertTrue(unlocked.getIsActive(), "Account should be unlocked");
    }
}
