package com.apms.domain.financial.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.financial.*;
import com.apms.domain.financial.dto.FinancialResearchResponse;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.user.Account;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialResearchCompanyMatchTest {

    @Mock
    private FinancialResearchRepository researchRepository;
    @Mock
    private ProjectTaskRepository projectTaskRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private DocumentCompanyMatcher companyMatcher;

    @InjectMocks
    private FinancialResearchService researchService;

    private final Long projectId = 1L;
    private final Long taskId = 100L;
    private final Long staffId = 42L;

    private Project project;
    private ProjectTask task;
    private Account staffAccount;
    private FinancialResearch research;
    private FinancialReportEntry report;

    @BeforeEach
    void setUp() {
        staffAccount = Account.builder()
                .id(staffId)
                .email("staff@apms.com")
                .passwordHash("hash")
                .build();

        project = Project.builder()
                .id(projectId)
                .projectName("Project Alpha")
                .targetCompanyName("Alpha Corp")
                .build();

        task = ProjectTask.builder()
                .id(taskId)
                .project(project)
                .taskType(TaskType.FINANCIAL_RESEARCH)
                .status(TaskStatus.IN_PROGRESS)
                .assignedToAccount(staffAccount)
                .build();

        report = FinancialReportEntry.builder()
                .id("rep-1")
                .title("2024 Financial Report")
                .documentId("doc-1")
                .extractionStatus(ExtractionStatus.EXTRACTED)
                .documentContext(DocumentContext.builder()
                        .companyName("Alpha Corp")
                        .companyValidation(DocumentCompanyValidationStatus.MATCH)
                        .companyVerifiedByStaff(true)
                        .build())
                .build();

        research = FinancialResearch.builder()
                .id("fr-1")
                .projectId(projectId)
                .taskId(taskId)
                .status(FinancialResearchStatus.DRAFT)
                .reports(new ArrayList<>(List.of(report)))
                .metrics(new ArrayList<>())
                .build();
    }

    private void mockSecurityContext(Long userId, String role) {
        UserDetailsImpl userDetails = new UserDetailsImpl(
                userId,
                "user@apms.com",
                "hash",
                Collections.singletonList(new SimpleGrantedAuthority(role)),
                true
        );
        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getPrincipal()).thenReturn(userDetails);
        lenient().when(auth.getAuthorities()).thenReturn((java.util.Collection) Collections.singletonList(new SimpleGrantedAuthority(role)));
        SecurityContext securityContext = mock(SecurityContext.class);
        lenient().when(securityContext.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    @DisplayName("FIN-COMPANY-001: MATCH status auto-confirms company match (verified=true, staffId=null, at=null)")
    void testMatchStatusAutoConfirms() {
        DocumentContext ctx = DocumentContext.builder()
                .companyName("Alpha Corp")
                .companyValidation(DocumentCompanyValidationStatus.MATCH)
                .companyVerifiedByStaff(true)
                .companyVerifiedByStaffId(null)
                .companyVerifiedAt(null)
                .build();

        assertThat(ctx.getCompanyValidation()).isEqualTo(DocumentCompanyValidationStatus.MATCH);
        assertThat(ctx.getCompanyVerifiedByStaff()).isTrue();
        assertThat(ctx.getCompanyVerifiedByStaffId()).isNull();
        assertThat(ctx.getCompanyVerifiedAt()).isNull();
        assertThat(FinancialResearchService.requiresCompanyConfirmation(ctx)).isFalse();
    }

    @Test
    @DisplayName("FIN-COMPANY-002: POSSIBLE_MATCH status requires manual confirmation and blocks submission")
    void testPossibleMatchRequiresConfirmation() {
        DocumentContext ctx = DocumentContext.builder()
                .companyName("Alpha Corporation")
                .companyValidation(DocumentCompanyValidationStatus.POSSIBLE_MATCH)
                .companyVerifiedByStaff(false)
                .build();

        assertThat(ctx.getCompanyValidation()).isEqualTo(DocumentCompanyValidationStatus.POSSIBLE_MATCH);
        assertThat(ctx.getCompanyVerifiedByStaff()).isFalse();
        assertThat(FinancialResearchService.requiresCompanyConfirmation(ctx)).isTrue();

        report.setDocumentContext(ctx);

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.submitForReview(projectId, taskId, staffId, List.of("rep-1"))
        );
        assertThat(ex.getErrorCode()).isEqualTo("COMPANY_MATCH_UNCONFIRMED");
    }

    @Test
    @DisplayName("FIN-COMPANY-003: MISMATCH status requires manual confirmation and blocks submission")
    void testMismatchRequiresConfirmation() {
        DocumentContext ctx = DocumentContext.builder()
                .companyName("Beta Corp")
                .companyValidation(DocumentCompanyValidationStatus.MISMATCH)
                .companyVerifiedByStaff(false)
                .build();

        assertThat(ctx.getCompanyValidation()).isEqualTo(DocumentCompanyValidationStatus.MISMATCH);
        assertThat(ctx.getCompanyVerifiedByStaff()).isFalse();
        assertThat(FinancialResearchService.requiresCompanyConfirmation(ctx)).isTrue();

        report.setDocumentContext(ctx);

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.submitForReview(projectId, taskId, staffId, List.of("rep-1"))
        );
        assertThat(ex.getErrorCode()).isEqualTo("COMPANY_MATCH_UNCONFIRMED");
    }

    @Test
    @DisplayName("FIN-COMPANY-004: UNKNOWN status requires manual confirmation and blocks submission")
    void testUnknownRequiresConfirmation() {
        DocumentContext ctx = DocumentContext.builder()
                .companyValidation(DocumentCompanyValidationStatus.UNKNOWN)
                .companyVerifiedByStaff(false)
                .build();

        assertThat(ctx.getCompanyValidation()).isEqualTo(DocumentCompanyValidationStatus.UNKNOWN);
        assertThat(ctx.getCompanyVerifiedByStaff()).isFalse();
        assertThat(FinancialResearchService.requiresCompanyConfirmation(ctx)).isTrue();

        report.setDocumentContext(ctx);

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.submitForReview(projectId, taskId, staffId, List.of("rep-1"))
        );
        assertThat(ex.getErrorCode()).isEqualTo("COMPANY_MATCH_UNCONFIRMED");
    }

    @Test
    @DisplayName("FIN-COMPANY-005: Manual confirmation unblocks submission")
    void testManualConfirmationUnblocksSubmission() {
        mockSecurityContext(staffId, "ROLE_BUSINESS_DEVELOPMENT_STAFF");
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(projectId, staffId)).thenReturn(true);

        report.setDocumentContext(DocumentContext.builder()
                .companyName("Beta Corp")
                .companyValidation(DocumentCompanyValidationStatus.MISMATCH)
                .companyVerifiedByStaff(false)
                .build());

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        FinancialResearchResponse response = researchService.confirmCompanyMatch(projectId, taskId, "rep-1", true, staffId);

        assertThat(report.getDocumentContext().getCompanyVerifiedByStaff()).isTrue();
        assertThat(report.getDocumentContext().getCompanyVerifiedByStaffId()).isEqualTo(staffId);
        assertThat(report.getDocumentContext().getCompanyVerifiedAt()).isNotNull();
        assertThat(FinancialResearchService.requiresCompanyConfirmation(report.getDocumentContext())).isFalse();

        verify(auditLogService).log(eq(staffId), eq(AuditAction.PROJECT_TASK_UPDATED), eq("ProjectTask"), eq(taskId.toString()), anyString());
    }

    @Test
    @DisplayName("FIN-COMPANY-006: Resetting confirmation sets staffVerified to false")
    void testResettingManualConfirmation() {
        mockSecurityContext(staffId, "ROLE_BUSINESS_DEVELOPMENT_STAFF");
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(projectId, staffId)).thenReturn(true);

        // Previous manual confirmation was true for a POSSIBLE_MATCH report
        report.getDocumentContext().setCompanyValidation(DocumentCompanyValidationStatus.POSSIBLE_MATCH);
        report.getDocumentContext().setCompanyVerifiedByStaff(true);
        report.getDocumentContext().setCompanyVerifiedByStaffId(staffId);
        report.getDocumentContext().setCompanyVerifiedAt(LocalDateTime.now());

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        FinancialResearchResponse response = researchService.confirmCompanyMatch(projectId, taskId, "rep-1", false, staffId);

        assertThat(report.getDocumentContext().getCompanyVerifiedByStaff()).isFalse();
        assertThat(report.getDocumentContext().getCompanyVerifiedByStaffId()).isNull();
        assertThat(report.getDocumentContext().getCompanyVerifiedAt()).isNull();
        assertThat(FinancialResearchService.requiresCompanyConfirmation(report.getDocumentContext())).isTrue();
    }

    @Test
    @DisplayName("FIN-COMPANY-007: Authorization: non-member or non-assigned cannot confirm")
    void testUnauthorizedCannotConfirm() {
        Long outsiderId = 999L;
        mockSecurityContext(outsiderId, "ROLE_BUSINESS_DEVELOPMENT_STAFF");
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(projectId, outsiderId)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () ->
                researchService.confirmCompanyMatch(projectId, taskId, "rep-1", true, outsiderId)
        );
    }

    @Test
    @DisplayName("FIN-COMPANY-008: Immutable: cannot confirm in APPROVED report status")
    void testCannotConfirmApprovedReport() {
        mockSecurityContext(staffId, "ROLE_BUSINESS_DEVELOPMENT_STAFF");
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(projectId, staffId)).thenReturn(true);

        report.setReviewStatus(FinancialReportReviewStatus.APPROVED);
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.confirmCompanyMatch(projectId, taskId, "rep-1", true, staffId)
        );
        assertThat(ex.getErrorCode()).isEqualTo("REPORT_APPROVED_IMMUTABLE");
    }

    @Test
    @DisplayName("FIN-METRIC-001: Unverify all metrics for a report")
    void testUnverifyAllMetricsForReport() {
        FinancialMetric m1 = FinancialMetric.builder()
                .id("m-1")
                .label("Revenue")
                .source(com.apms.domain.financial.MetricSource.builder()
                        .reportEntryId("rep-1")
                        .documentId("doc-1")
                        .build())
                .verificationStatus(MetricVerificationStatus.VERIFIED)
                .build();

        FinancialMetric m2 = FinancialMetric.builder()
                .id("m-2")
                .label("Profit")
                .source(com.apms.domain.financial.MetricSource.builder()
                        .reportEntryId("rep-1")
                        .documentId("doc-1")
                        .build())
                .verificationStatus(MetricVerificationStatus.VERIFIED)
                .build();

        research.setMetrics(new ArrayList<>(List.of(m1, m2)));

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        FinancialResearchResponse resp = researchService.unverifyAllMetricsForReport(projectId, taskId, "rep-1");

        assertThat(m1.getVerificationStatus()).isEqualTo(MetricVerificationStatus.UNVERIFIED);
        assertThat(m2.getVerificationStatus()).isEqualTo(MetricVerificationStatus.UNVERIFIED);
    }
}
