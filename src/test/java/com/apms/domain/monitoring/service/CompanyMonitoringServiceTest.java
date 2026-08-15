package com.apms.domain.monitoring.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.MonitoringFrequency;
import com.apms.common.enums.MonitoringReviewResult;
import com.apms.common.enums.MonitoringStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.monitoring.dto.CompanyMonitoringAssignmentRequest;
import com.apms.domain.monitoring.dto.CompanyMonitoringAssignmentResponse;
import com.apms.domain.monitoring.dto.CompanyMonitoringReviewRequest;
import com.apms.domain.monitoring.dto.CompanyMonitoringReviewResponse;
import com.apms.domain.monitoring.model.CompanyMonitoringAssignment;
import com.apms.domain.monitoring.model.CompanyMonitoringReview;
import com.apms.domain.monitoring.repository.CompanyMonitoringAssignmentRepository;
import com.apms.domain.monitoring.repository.CompanyMonitoringReviewRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.common.enums.SystemRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyMonitoringServiceTest {

    @Mock
    private CompanyMonitoringAssignmentRepository assignmentRepository;

    @Mock
    private CompanyMonitoringReviewRepository reviewRepository;

    @Mock
    private CompanyProfileRepository companyProfileRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CompanyProfileUpdateProposalRepository proposalRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private CompanyMonitoringService service;

    private Account manager;
    private Account staff;
    private CompanyProfile companyProfile;
    private CompanyMonitoringAssignment assignment;

    @BeforeEach
    void setUp() {
        manager = new Account();
        manager.setId(1L);
        manager.setEmail("manager@apms.com");
        manager.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_MANAGER));

        staff = new Account();
        staff.setId(2L);
        staff.setEmail("staff@apms.com");
        staff.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));

        companyProfile = new CompanyProfile();
        companyProfile.setId("profile-1");
        companyProfile.setIdentity(new CompanyProfile.Identity());

        assignment = CompanyMonitoringAssignment.builder()
                .id(100L)
                .companyProfileId("profile-1")
                .assignedStaff(staff)
                .assignedByManager(manager)
                .frequency(MonitoringFrequency.MONTHLY)
                .status(MonitoringStatus.ACTIVE)
                .nextReviewAt(LocalDateTime.now().plusMonths(1))
                .build();
    }

    @Test
    void assignMonitor_Success_NewAssignment() {
        CompanyMonitoringAssignmentRequest request = new CompanyMonitoringAssignmentRequest();
        request.setCompanyProfileId("profile-1");
        request.setAssignedStaffId(2L);
        request.setFrequency(MonitoringFrequency.MONTHLY);

        when(companyProfileRepository.findById("profile-1")).thenReturn(Optional.of(companyProfile));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(manager));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(staff));
        when(assignmentRepository.findByCompanyProfileId("profile-1")).thenReturn(Optional.empty());
        
        when(assignmentRepository.save(any(CompanyMonitoringAssignment.class))).thenAnswer(invocation -> {
            CompanyMonitoringAssignment a = invocation.getArgument(0);
            a.setId(101L);
            return a;
        });

        CompanyMonitoringAssignmentResponse response = service.assignMonitor(request, 1L);

        assertNotNull(response);
        assertEquals(101L, response.getId());
        assertEquals("profile-1", response.getCompanyProfileId());
        assertEquals(2L, response.getAssignedStaffId());
        
        verify(assignmentRepository).save(any(CompanyMonitoringAssignment.class));
        verify(auditLogService).log(eq(1L), eq(AuditAction.MONITORING_ASSIGNED), eq("CompanyMonitoringAssignment"), eq("101"), anyString());
    }

    @Test
    void submitReview_Success_NoChange() {
        CompanyMonitoringReviewRequest request = new CompanyMonitoringReviewRequest();
        request.setResult(MonitoringReviewResult.NO_CHANGE);
        request.setNote("Everything is good");

        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(staff));
        when(reviewRepository.save(any(CompanyMonitoringReview.class))).thenAnswer(invocation -> {
            CompanyMonitoringReview r = invocation.getArgument(0);
            r.setId(200L);
            return r;
        });

        CompanyMonitoringReviewResponse response = service.submitReview(100L, request, 2L);

        assertNotNull(response);
        assertEquals(200L, response.getId());
        assertEquals(MonitoringReviewResult.NO_CHANGE, response.getResult());
        assertNotNull(assignment.getLastReviewedAt());

        verify(reviewRepository).save(any(CompanyMonitoringReview.class));
        verify(assignmentRepository).save(assignment);
        verify(auditLogService).log(eq(2L), eq(AuditAction.MONITORING_REVIEW_COMPLETED), eq("CompanyMonitoringAssignment"), eq("100"), anyString());
    }

    @Test
    void submitReview_Success_UpdateProposed() {
        CompanyMonitoringReviewRequest request = new CompanyMonitoringReviewRequest();
        request.setResult(MonitoringReviewResult.UPDATE_PROPOSED);
        request.setUpdateProposalId("proposal-1");
        request.setNote("Found some updates");

        CompanyProfileUpdateProposal proposal = CompanyProfileUpdateProposal.builder()
                .id("proposal-1")
                .companyProfileId("profile-1")
                .build();

        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(staff));
        when(proposalRepository.findById("proposal-1")).thenReturn(Optional.of(proposal));
        when(reviewRepository.save(any(CompanyMonitoringReview.class))).thenAnswer(invocation -> {
            CompanyMonitoringReview r = invocation.getArgument(0);
            r.setId(201L);
            return r;
        });

        CompanyMonitoringReviewResponse response = service.submitReview(100L, request, 2L);

        assertNotNull(response);
        assertEquals(201L, response.getId());
        assertEquals(MonitoringReviewResult.UPDATE_PROPOSED, response.getResult());
        assertEquals("proposal-1", response.getUpdateProposalId());

        verify(auditLogService).log(eq(2L), eq(AuditAction.MONITORING_UPDATE_PROPOSED), eq("CompanyMonitoringAssignment"), eq("100"), anyString());
    }
}
