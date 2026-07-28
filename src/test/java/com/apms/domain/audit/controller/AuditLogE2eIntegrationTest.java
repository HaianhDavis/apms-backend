package com.apms.domain.audit.controller;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.domain.admin.dto.CreateAccountRequest;
import com.apms.domain.admin.service.AdminUserService;
import com.apms.domain.audit.AuditLog;
import com.apms.domain.audit.repository.sql.AuditLogRepository;
import com.apms.domain.audit.service.AuditLogQueryService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AuditLogE2eIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private AuditLogQueryService auditLogQueryService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AccountRepository accountRepository;

    private Account adminAccount;
    private Account targetAccount;

    @BeforeEach
    void setUp() {
        adminAccount = accountRepository.findByEmail("admin@apms.com")
                .orElseGet(() -> accountRepository.save(Account.builder()
                        .email("admin@apms.com").username("admin").passwordHash("hash").isActive(true).roles(Set.of(SystemRole.SYSTEM_ADMIN)).build()));

        targetAccount = accountRepository.findByEmail("staff@apms.com")
                .orElseGet(() -> accountRepository.save(Account.builder()
                        .email("staff@apms.com").username("staff").passwordHash("hash").isActive(true).roles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF)).build()));
    }

    @Test
    @DisplayName("Audit E2E 1: User Status Change creates USER_STATUS_CHANGED audit log in DB")
    void e2e1_updateUserStatus_shouldRecordAuditLog() {
        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setEnabled(false);

        userService.updateUserStatus(targetAccount.getId(), req, adminAccount.getId());

        List<AuditLog> logs = auditLogRepository.findAll();
        boolean hasLog = logs.stream().anyMatch(l -> 
                l.getAction() == AuditAction.USER_STATUS_CHANGED && 
                l.getActorAccount().getId().equals(adminAccount.getId()));

        assertTrue(hasLog, "Audit log for USER_STATUS_CHANGED MUST be recorded in database");
    }

    @Test
    @DisplayName("Audit E2E 2: User Roles Assignment creates USER_ROLES_UPDATED audit log in DB")
    void e2e2_assignUserRoles_shouldRecordAuditLog() {
        AssignUserRolesRequest req = new AssignUserRolesRequest();
        req.setRoles(Set.of(SystemRole.KEY_MEMBER));

        userService.assignUserRoles(targetAccount.getId(), req, adminAccount.getId());

        List<AuditLog> logs = auditLogRepository.findAll();
        boolean hasLog = logs.stream().anyMatch(l -> 
                l.getAction() == AuditAction.USER_ROLES_UPDATED && 
                l.getActorAccount().getId().equals(adminAccount.getId()));

        assertTrue(hasLog, "Audit log for USER_ROLES_UPDATED MUST be recorded in database");
    }

    @Test
    @DisplayName("Audit E2E 3: Search audit logs with action filter returns matched records")
    void e2e3_searchAuditLogsWithActionFilter_shouldReturnMatchedRecords() {
        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setEnabled(false);
        userService.updateUserStatus(targetAccount.getId(), req, adminAccount.getId());

        Page<com.apms.domain.audit.dto.AuditLogResponse> pageRes = auditLogQueryService.searchAuditLogs(
                adminAccount.getId(), "USER_STATUS_CHANGED", "Account", null, null, null, PageRequest.of(0, 10));

        assertNotNull(pageRes);
        assertTrue(pageRes.getContent().stream().anyMatch(l -> l.getAction().equals("USER_STATUS_CHANGED")));
    }

    @Test
    @DisplayName("Audit E2E 4: Export CSV generates valid CSV content containing audit headers & rows")
    void e2e4_exportAuditLogs_shouldGenerateValidCsvBytes() {
        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setEnabled(false);
        userService.updateUserStatus(targetAccount.getId(), req, adminAccount.getId());

        byte[] csvBytes = auditLogQueryService.exportAuditLogs(adminAccount.getId(), "USER_STATUS_CHANGED", "Account", null, null, null);
        assertNotNull(csvBytes);
        assertTrue(csvBytes.length > 0);

        String csvString = new String(csvBytes);
        assertTrue(csvString.contains("ID,Actor User ID,Actor Email,Action,Entity Type,Entity ID,Details,Created At"));
        assertTrue(csvString.contains("USER_STATUS_CHANGED"));
    }

    @Test
    @DisplayName("Audit E2E 5: Export CSV sanitizes formula injection characters (=, +, -, @) with single quote prefix")
    void e2e5_exportAuditLogs_withFormulaInjectionCharacters_shouldBeSanitizedWithSingleQuote() {
        com.apms.domain.audit.service.AuditLogService auditLogService = (com.apms.domain.audit.service.AuditLogService) 
                org.springframework.test.util.ReflectionTestUtils.getField(userService, "auditLogService");

        assertNotNull(auditLogService);
        auditLogService.log(adminAccount.getId(), AuditAction.USER_CREATED, "Account", "99", "=SUM(1+1) malicious payload");

        byte[] csvBytes = auditLogQueryService.exportAuditLogs(adminAccount.getId(), "USER_CREATED", "Account", null, null, null);
        String csvString = new String(csvBytes);

        assertTrue(csvString.contains("'=SUM(1+1) malicious payload"), "Formula injection payload starting with '=' MUST be prefixed with single quote");
    }
}
