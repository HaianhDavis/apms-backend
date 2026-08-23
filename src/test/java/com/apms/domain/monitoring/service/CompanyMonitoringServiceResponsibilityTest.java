package com.apms.domain.monitoring.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.monitoring.dto.CompanyMonitoringAssignmentRequest;
import com.apms.domain.monitoring.repository.CompanyMonitoringAssignmentRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CompanyMonitoringServiceResponsibilityTest {

    @Mock
    private CompanyMonitoringAssignmentRepository assignmentRepository;

    @Mock
    private CompanyProfileRepository companyProfileRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AuditLogService auditLogService;
    
    @Mock
    private com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository proposalRepository;
    @InjectMocks
    private CompanyMonitoringService companyMonitoringService;

    private CompanyProfile profile;
    private Account manager;
    private Account otherManager;
    private Account systemAdmin;
    private Account staff;

    @BeforeEach
    void setUp() {
        profile = CompanyProfile.builder()
                .id("profile1")
                .companyId("profile1")
                .responsibleManagerId(10L)
                .build();

        manager = Account.builder().id(10L).roles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_MANAGER)).build();
        otherManager = Account.builder().id(20L).roles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_MANAGER)).build();
        systemAdmin = Account.builder().id(30L).roles(Set.of(SystemRole.SYSTEM_ADMIN)).build();
        staff = Account.builder().id(40L).roles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF)).build();
    }

    @Test
    void assignMonitor_ResponsibleManager_Success() {
        CompanyMonitoringAssignmentRequest req = new CompanyMonitoringAssignmentRequest();
        req.setCompanyProfileId("profile1");
        req.setAssignedStaffId(40L);
        req.setFrequency(com.apms.common.enums.MonitoringFrequency.MONTHLY);

        when(companyProfileRepository.findById("profile1")).thenReturn(Optional.of(profile));
        when(accountRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(accountRepository.findById(40L)).thenReturn(Optional.of(staff));
        when(assignmentRepository.findByCompanyProfileId("profile1")).thenReturn(Optional.empty());
        when(assignmentRepository.save(any())).thenAnswer(invocation -> {
            com.apms.domain.monitoring.model.CompanyMonitoringAssignment a = invocation.getArgument(0);
            a.setId(100L);
            return a;
        });

        companyMonitoringService.assignMonitor(req, 10L);

        verify(assignmentRepository).save(any());
    }

    @Test
    void assignMonitor_OtherManager_ThrowsAccessDenied() {
        CompanyMonitoringAssignmentRequest req = new CompanyMonitoringAssignmentRequest();
        req.setCompanyProfileId("profile1");
        req.setAssignedStaffId(40L);

        when(companyProfileRepository.findById("profile1")).thenReturn(Optional.of(profile));
        when(accountRepository.findById(20L)).thenReturn(Optional.of(otherManager));

        assertThrows(AccessDeniedException.class, () -> {
            companyMonitoringService.assignMonitor(req, 20L);
        });
    }

    @Test
    void assignMonitor_SystemAdmin_Success() {
        CompanyMonitoringAssignmentRequest req = new CompanyMonitoringAssignmentRequest();
        req.setCompanyProfileId("profile1");
        req.setAssignedStaffId(40L);
        req.setFrequency(com.apms.common.enums.MonitoringFrequency.MONTHLY);

        when(companyProfileRepository.findById("profile1")).thenReturn(Optional.of(profile));
        when(accountRepository.findById(30L)).thenReturn(Optional.of(systemAdmin));
        when(accountRepository.findById(40L)).thenReturn(Optional.of(staff));
        when(assignmentRepository.findByCompanyProfileId("profile1")).thenReturn(Optional.empty());
        when(assignmentRepository.save(any())).thenAnswer(invocation -> {
            com.apms.domain.monitoring.model.CompanyMonitoringAssignment a = invocation.getArgument(0);
            a.setId(100L);
            return a;
        });

        companyMonitoringService.assignMonitor(req, 30L);

        verify(assignmentRepository).save(any());
    }
}
