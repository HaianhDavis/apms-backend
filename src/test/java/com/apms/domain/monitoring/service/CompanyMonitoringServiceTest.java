//package com.apms.domain.monitoring.service;
//
//import com.apms.common.enums.AuditAction;
//import com.apms.common.enums.MonitoringFrequency;
//import com.apms.common.enums.MonitoringReviewResult;
//import com.apms.common.enums.MonitoringStatus;
//import com.apms.common.enums.ProposalOrigin;
//import com.apms.common.enums.SubmissionStatus;
//import com.apms.domain.audit.service.AuditLogService;
//import com.apms.domain.monitoring.dto.CompanyMonitoringAssignmentRequest;
//import com.apms.domain.monitoring.dto.CompanyMonitoringAssignmentResponse;
//import com.apms.domain.monitoring.dto.CompanyMonitoringReviewRequest;
//import com.apms.domain.monitoring.dto.CompanyMonitoringReviewResponse;
//import com.apms.domain.monitoring.model.CompanyMonitoringAssignment;
//import com.apms.domain.monitoring.model.CompanyMonitoringReview;
//import com.apms.domain.monitoring.repository.CompanyMonitoringAssignmentRepository;
//import com.apms.domain.monitoring.repository.CompanyMonitoringReviewRepository;
//import com.apms.domain.monitoring.repository.CompanyRelationshipChangeProposalRepository;
//import com.apms.domain.profile.CompanyProfile;
//import com.apms.domain.profile.CompanyProfileUpdateProposal;
//import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
//import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
//import com.apms.domain.user.Account;
//import com.apms.domain.user.repository.sql.AccountRepository;
//import com.apms.common.enums.SystemRole;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.PageImpl;
//import org.springframework.data.domain.PageRequest;
//
//import java.time.LocalDateTime;
//import java.util.List;
//import java.util.Optional;
//import java.util.Set;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//class CompanyMonitoringServiceTest {
//
//    @Mock
//    private CompanyMonitoringAssignmentRepository assignmentRepository;
//
//    @Mock
//    private CompanyMonitoringReviewRepository reviewRepository;
//
//    @Mock
//    private CompanyProfileRepository companyProfileRepository;
//
//    @Mock
//    private AccountRepository accountRepository;
//
//    @Mock
//    private CompanyProfileUpdateProposalRepository proposalRepository;
//
//    @Mock
//    private CompanyRelationshipChangeProposalRepository relationshipChangeProposalRepository;
//
//    @Mock
//    private AuditLogService auditLogService;
//
//    @Mock
//    private com.apms.domain.profile.service.CompanyProfileVersionService versionService;
//
//    @InjectMocks
//    private CompanyMonitoringService service;
//
//    private Account manager;
//    private Account staff;
//    private CompanyProfile companyProfile;
//    private CompanyMonitoringAssignment assignment;
//
//    @BeforeEach
//    void setUp() {
//        manager = new Account();
//        manager.setId(1L);
//        manager.setEmail("manager@apms.com");
//        manager.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_MANAGER));
//
//        staff = new Account();
//        staff.setId(2L);
//        staff.setEmail("staff@apms.com");
//        staff.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));
//
//        companyProfile = new CompanyProfile();
//        companyProfile.setId("profile-1");
//        companyProfile.setCompanyId("profile-1");
//        companyProfile.setIdentity(new CompanyProfile.Identity());
//        companyProfile.setResponsibleManagerId(1L);
//
//        assignment = CompanyMonitoringAssignment.builder()
//                .id(100L)
//                .companyProfileId("profile-1")
//                .assignedStaff(staff)
//                .assignedByManager(manager)
//                .frequency(MonitoringFrequency.MONTHLY)
//                .status(MonitoringStatus.ACTIVE)
//                .nextReviewAt(LocalDateTime.now().plusMonths(1))
//                .build();
//    }
//
//    @Test
//    void assignMonitor_Success_NewAssignment() {
//        CompanyMonitoringAssignmentRequest request = new CompanyMonitoringAssignmentRequest();
//        request.setCompanyProfileId("profile-1");
//        request.setAssignedStaffId(2L);
//        request.setFrequency(MonitoringFrequency.MONTHLY);
//
//        when(companyProfileRepository.findById("profile-1")).thenReturn(Optional.of(companyProfile));
//        when(accountRepository.findById(1L)).thenReturn(Optional.of(manager));
//        when(accountRepository.findById(2L)).thenReturn(Optional.of(staff));
//        when(assignmentRepository.findByCompanyProfileId("profile-1")).thenReturn(Optional.empty());
//
//        when(assignmentRepository.save(any(CompanyMonitoringAssignment.class))).thenAnswer(invocation -> {
//            CompanyMonitoringAssignment a = invocation.getArgument(0);
//            a.setId(101L);
//            return a;
//        });
//
//        CompanyMonitoringAssignmentResponse response = service.assignMonitor(request, 1L);
//
//        assertNotNull(response);
//        assertEquals(101L, response.getId());
//        assertEquals("profile-1", response.getCompanyProfileId());
//        assertEquals(2L, response.getAssignedStaffId());
//
//        verify(assignmentRepository).save(any(CompanyMonitoringAssignment.class));
//        verify(auditLogService).log(eq(1L), eq(AuditAction.MONITORING_ASSIGNED), eq("CompanyMonitoringAssignment"), eq("101"), anyString());
//    }
//
//    @Test
//    void submitReview_Success_NoChange() {
//        CompanyMonitoringReviewRequest request = new CompanyMonitoringReviewRequest();
//        request.setResult(MonitoringReviewResult.NO_CHANGE);
//        request.setNote("Everything is good");
//
//        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment));
//        when(accountRepository.findById(2L)).thenReturn(Optional.of(staff));
//        when(reviewRepository.save(any(CompanyMonitoringReview.class))).thenAnswer(invocation -> {
//            CompanyMonitoringReview r = invocation.getArgument(0);
//            r.setId(200L);
//            return r;
//        });
//
//        CompanyMonitoringReviewResponse response = service.submitReview(100L, request, 2L);
//
//        assertNotNull(response);
//        assertEquals(200L, response.getId());
//        assertEquals(MonitoringReviewResult.NO_CHANGE, response.getResult());
//        assertNotNull(assignment.getLastReviewedAt());
//
//        verify(reviewRepository).save(any(CompanyMonitoringReview.class));
//        verify(assignmentRepository).save(assignment);
//        verify(auditLogService).log(eq(2L), eq(AuditAction.MONITORING_REVIEW_COMPLETED), eq("CompanyMonitoringAssignment"), eq("100"), anyString());
//    }
//
//    @Test
//    void submitReview_Success_UpdateProposed() {
//        CompanyMonitoringReviewRequest request = new CompanyMonitoringReviewRequest();
//        request.setResult(MonitoringReviewResult.UPDATE_PROPOSED);
//        request.setUpdateProposalId("proposal-1");
//        request.setNote("Found some updates");
//
//        CompanyProfileUpdateProposal proposal = CompanyProfileUpdateProposal.builder()
//                .id("proposal-1")
//                .companyProfileId("profile-1")
//                .origin(ProposalOrigin.MONITORING)
//                .build();
//
//        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment));
//        when(accountRepository.findById(2L)).thenReturn(Optional.of(staff));
//        when(proposalRepository.findById("proposal-1")).thenReturn(Optional.of(proposal));
//        when(reviewRepository.save(any(CompanyMonitoringReview.class))).thenAnswer(invocation -> {
//            CompanyMonitoringReview r = invocation.getArgument(0);
//            r.setId(201L);
//            return r;
//        });
//
//        CompanyMonitoringReviewResponse response = service.submitReview(100L, request, 2L);
//
//        assertNotNull(response);
//        assertEquals(201L, response.getId());
//        assertEquals(MonitoringReviewResult.UPDATE_PROPOSED, response.getResult());
//        assertEquals("proposal-1", response.getUpdateProposalId());
//        assertEquals(SubmissionStatus.SUBMITTED.name(), response.getProposalStatus());
//
//        verify(proposalRepository).save(proposal);
//        verify(auditLogService).log(eq(2L), eq(AuditAction.MONITORING_UPDATE_PROPOSED), eq("CompanyMonitoringAssignment"), eq("100"), anyString());
//    }
//
//    @Test
//    void getMonitoringHistory_ManagerScope_IncludesNoChangeAndMapsProposalStatus() {
//        CompanyMonitoringReview noChange = CompanyMonitoringReview.builder()
//                .id(200L)
//                .assignment(assignment)
//                .companyProfileId("profile-1")
//                .reviewedBy(staff)
//                .reviewedAt(LocalDateTime.now().minusDays(1))
//                .result(MonitoringReviewResult.NO_CHANGE)
//                .note("No changes found")
//                .build();
//
//        CompanyMonitoringReview updateProposed = CompanyMonitoringReview.builder()
//                .id(201L)
//                .assignment(assignment)
//                .companyProfileId("profile-1")
//                .reviewedBy(staff)
//                .reviewedAt(LocalDateTime.now())
//                .result(MonitoringReviewResult.UPDATE_PROPOSED)
//                .updateProposalId("proposal-1")
//                .note("Update proposed")
//                .build();
//
//        CompanyProfileUpdateProposal approvedProposal = CompanyProfileUpdateProposal.builder()
//                .id("proposal-1")
//                .companyProfileId("profile-1")
//                .status(SubmissionStatus.APPROVED)
//                .build();
//
//        when(accountRepository.findById(1L)).thenReturn(Optional.of(manager));
//        when(companyProfileRepository.findByResponsibleManagerId(1L)).thenReturn(List.of(companyProfile));
//        when(reviewRepository.findByCompanyProfileIdIn(eq(Set.of("profile-1")), any(PageRequest.class)))
//                .thenReturn(new PageImpl<>(List.of(updateProposed, noChange), PageRequest.of(0, 10), 2));
//        when(companyProfileRepository.findAllById(Set.of("profile-1"))).thenReturn(List.of(companyProfile));
//        when(proposalRepository.findAllById(List.of("proposal-1"))).thenReturn(List.of(approvedProposal));
//
//        Page<CompanyMonitoringReviewResponse> response = service.getMonitoringHistory(1L, PageRequest.of(0, 10));
//
//        assertEquals(2, response.getTotalElements());
//        assertEquals(MonitoringReviewResult.UPDATE_PROPOSED, response.getContent().get(0).getResult());
//        assertEquals(SubmissionStatus.APPROVED.name(), response.getContent().get(0).getProposalStatus());
//        assertEquals(MonitoringReviewResult.NO_CHANGE, response.getContent().get(1).getResult());
//        assertNull(response.getContent().get(1).getProposalStatus());
//        verify(reviewRepository).findByCompanyProfileIdIn(eq(Set.of("profile-1")), any(PageRequest.class));
//    }
//
//    @Test
//    void getAssignmentByCompany_NotAssigned_ReturnsEmpty() {
//        when(assignmentRepository.findByCompanyProfileId("profile-1")).thenReturn(Optional.empty());
//
//        Optional<CompanyMonitoringAssignmentResponse> response = service.getAssignmentByCompany("profile-1");
//
//        assertTrue(response.isEmpty());
//        verifyNoInteractions(companyProfileRepository);
//    }
//
//    @Test
//    void getAssignmentByCompany_Assigned_ReturnsAssignment() {
//        when(assignmentRepository.findByCompanyProfileId("profile-1")).thenReturn(Optional.of(assignment));
//        when(companyProfileRepository.findById("profile-1")).thenReturn(Optional.of(companyProfile));
//        lenient().when(proposalRepository.findTopByCompanyProfileIdAndOriginOrderByCreatedAtDesc(eq("profile-1"), any()))
//                .thenReturn(Optional.empty());
//
//        Optional<CompanyMonitoringAssignmentResponse> response = service.getAssignmentByCompany("profile-1");
//
//        assertTrue(response.isPresent());
//        assertEquals(100L, response.get().getId());
//        assertEquals("profile-1", response.get().getCompanyProfileId());
//    }
//}
