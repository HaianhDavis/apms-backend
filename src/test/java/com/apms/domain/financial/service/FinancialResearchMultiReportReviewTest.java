package com.apms.domain.financial.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.financial.*;
import com.apms.domain.financial.dto.FinancialResearchResponse;
import com.apms.domain.financial.dto.ReviewFinancialReportRequest;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialResearchMultiReportReviewTest {

    @Mock
    private FinancialResearchRepository researchRepository;
    @Mock
    private com.apms.domain.document.service.DocumentService documentService;
    @Mock
    private ProjectTaskRepository projectTaskRepository;
    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private FinancialResearchService researchService;

    private final Long projectId = 1L;
    private final Long taskId = 100L;
    private final Long managerId = 99L;
    private final Long staffId = 42L;

    private Project project;
    private ProjectTask task;
    private ProjectTaskSubmission submission;
    private UserDetailsImpl managerUserDetails;

    @BeforeEach
    void setUp() {
        project = Project.builder()
                .id(projectId)
                .projectName("Test Project")
                .targetCompanyProfileId("CP-123")
                .build();

        task = ProjectTask.builder()
                .id(taskId)
                .project(project)
                .status(TaskStatus.IN_REVIEW)
                .targetCompanyProfileId("CP-123")
                .build();

        submission = ProjectTaskSubmission.builder()
                .id(500L)
                .projectTask(task)
                .project(project)
                .status(SubmissionStatus.IN_REVIEW)
                .build();

        managerUserDetails = new UserDetailsImpl(
                managerId,
                "manager@apms.com",
                "hash",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_MANAGER")),
                true
        );

        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getPrincipal()).thenReturn(managerUserDetails);
        SecurityContext secContext = mock(SecurityContext.class);
        lenient().when(secContext.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(secContext);

        lenient().when(projectTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        lenient().when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private FinancialReportEntry buildReport(String id, String title, FinancialReportReviewStatus status) {
        return FinancialReportEntry.builder()
                .id(id)
                .title(title)
                .extractionStatus(ExtractionStatus.EXTRACTED)
                .reviewStatus(status)
                .build();
    }

    @Test
    @DisplayName("Test 1: Request changes on Report 1 when Report 2 is PENDING -> parent stays SUBMITTED / IN_REVIEW")
    void test1_RequestChangesOnReport1_WhenReport2Pending_StaysInReview() {
        FinancialReportEntry rep1 = buildReport("rep-1", "Report 1", FinancialReportReviewStatus.PENDING_REVIEW);
        FinancialReportEntry rep2 = buildReport("rep-2", "Report 2", FinancialReportReviewStatus.PENDING_REVIEW);

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.SUBMITTED)
                .submittedReportIds(new ArrayList<>(List.of("rep-1", "rep-2")))
                .reports(new ArrayList<>(List.of(rep1, rep2)))
                .metrics(new ArrayList<>())
                .build();

        submission.setTargetItemIdList(List.of("rep-1", "rep-2"));

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission));

        ReviewFinancialReportRequest request = new ReviewFinancialReportRequest();
        request.setStatus(FinancialReportReviewStatus.CHANGES_REQUESTED);
        request.setReason("Please verify Q1 charter capital");

        FinancialResearchResponse response = researchService.reviewReport(projectId, taskId, "rep-1", request);

        assertThat(response.getReports().get(0).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.CHANGES_REQUESTED);
        assertThat(response.getReports().get(1).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.PENDING_REVIEW);

        // Research and Task MUST remain IN_REVIEW because Report 2 is still pending
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.IN_REVIEW);
    }

    @Test
    @DisplayName("Test 2: Approve Report 2 when Report 1 has CHANGES_REQUESTED -> parent becomes CHANGES_REQUESTED / IN_PROGRESS")
    void test2_ApproveReport2_WhenReport1ChangesRequested_ReturnsToStaff() {
        FinancialReportEntry rep1 = buildReport("rep-1", "Report 1", FinancialReportReviewStatus.CHANGES_REQUESTED);
        rep1.setReviewComment("Please verify Q1 charter capital");
        FinancialReportEntry rep2 = buildReport("rep-2", "Report 2", FinancialReportReviewStatus.PENDING_REVIEW);

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.SUBMITTED)
                .submittedReportIds(new ArrayList<>(List.of("rep-1", "rep-2")))
                .reports(new ArrayList<>(List.of(rep1, rep2)))
                .metrics(new ArrayList<>())
                .build();

        submission.setTargetItemIdList(List.of("rep-1", "rep-2"));

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission));

        ReviewFinancialReportRequest request = new ReviewFinancialReportRequest();
        request.setStatus(FinancialReportReviewStatus.APPROVED);

        FinancialResearchResponse response = researchService.reviewReport(projectId, taskId, "rep-2", request);

        assertThat(response.getReports().get(0).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.CHANGES_REQUESTED);
        assertThat(response.getReports().get(1).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);

        // 0 pending remain, Report 1 is CHANGES_REQUESTED -> task returns to staff
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.CHANGES_REQUESTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.CHANGES_REQUESTED);
        assertThat(submission.getReviewComment()).contains("Report 1: Please verify Q1 charter capital");
    }

    @Test
    @DisplayName("Test 3: Approve Report 2 when Report 1 is APPROVED -> parent becomes APPROVED / DONE")
    void test3_ApproveReport2_WhenReport1Approved_TaskBecomesDone() {
        FinancialReportEntry rep1 = buildReport("rep-1", "Report 1", FinancialReportReviewStatus.APPROVED);
        FinancialReportEntry rep2 = buildReport("rep-2", "Report 2", FinancialReportReviewStatus.PENDING_REVIEW);

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.SUBMITTED)
                .submittedReportIds(new ArrayList<>(List.of("rep-1", "rep-2")))
                .reports(new ArrayList<>(List.of(rep1, rep2)))
                .metrics(new ArrayList<>())
                .build();

        submission.setTargetItemIdList(List.of("rep-1", "rep-2"));

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission));

        ReviewFinancialReportRequest request = new ReviewFinancialReportRequest();
        request.setStatus(FinancialReportReviewStatus.APPROVED);

        FinancialResearchResponse response = researchService.reviewReport(projectId, taskId, "rep-2", request);

        assertThat(response.getReports().get(0).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);
        assertThat(response.getReports().get(1).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);

        // All approved -> task DONE
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.APPROVED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
    }

    @Test
    @DisplayName("Test 4: Staff resubmits corrected Report 1 while Report 2 stays APPROVED")
    void test4_StaffResubmitsCorrectedReport_ApprovedReportRemainsApproved() {
        FinancialReportEntry rep1 = buildReport("rep-1", "Report 1", FinancialReportReviewStatus.CHANGES_REQUESTED);
        FinancialReportEntry rep2 = buildReport("rep-2", "Report 2", FinancialReportReviewStatus.APPROVED);

        FinancialMetric metric1 = FinancialMetric.builder()
                .id("m-1")
                .source(MetricSource.builder().reportEntryId("rep-1").build())
                .verificationStatus(MetricVerificationStatus.VERIFIED)
                .build();

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.CHANGES_REQUESTED)
                .reports(new ArrayList<>(List.of(rep1, rep2)))
                .metrics(new ArrayList<>(List.of(metric1)))
                .build();

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        // Staff submits ONLY rep-1
        FinancialResearchResponse response = researchService.submitForReview(projectId, taskId, staffId, List.of("rep-1"));

        assertThat(response.getReports().get(0).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.PENDING_REVIEW);
        assertThat(response.getReports().get(1).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.SUBMITTED);
        assertThat(research.getSubmittedReportIds()).containsExactly("rep-1");
    }

    @Test
    @DisplayName("Test 5: Manager approves resubmitted Report 1 -> all reports APPROVED -> task DONE")
    void test5_ManagerApprovesResubmittedReport_CompletesTask() {
        FinancialReportEntry rep1 = buildReport("rep-1", "Report 1", FinancialReportReviewStatus.PENDING_REVIEW);
        FinancialReportEntry rep2 = buildReport("rep-2", "Report 2", FinancialReportReviewStatus.APPROVED);

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.SUBMITTED)
                .submittedReportIds(new ArrayList<>(List.of("rep-1")))
                .reports(new ArrayList<>(List.of(rep1, rep2)))
                .metrics(new ArrayList<>())
                .build();

        submission.setTargetItemIdList(List.of("rep-1"));

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission));

        ReviewFinancialReportRequest request = new ReviewFinancialReportRequest();
        request.setStatus(FinancialReportReviewStatus.APPROVED);

        FinancialResearchResponse response = researchService.reviewReport(projectId, taskId, "rep-1", request);

        assertThat(response.getReports().get(0).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);
        assertThat(response.getReports().get(1).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);

        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.APPROVED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
    }

    @Test
    @DisplayName("Test 6: 3 Reports review sequence: Report 1 APPROVED, Report 2 CHANGES_REQUESTED -> stays IN_REVIEW until Report 3 reviewed")
    void test6_ThreeReportsSequence_StaysInReviewUntilAllReviewed() {
        FinancialReportEntry rep1 = buildReport("rep-1", "Report 1", FinancialReportReviewStatus.PENDING_REVIEW);
        FinancialReportEntry rep2 = buildReport("rep-2", "Report 2", FinancialReportReviewStatus.PENDING_REVIEW);
        FinancialReportEntry rep3 = buildReport("rep-3", "Report 3", FinancialReportReviewStatus.PENDING_REVIEW);

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.SUBMITTED)
                .submittedReportIds(new ArrayList<>(List.of("rep-1", "rep-2", "rep-3")))
                .reports(new ArrayList<>(List.of(rep1, rep2, rep3)))
                .metrics(new ArrayList<>())
                .build();

        submission.setTargetItemIdList(List.of("rep-1", "rep-2", "rep-3"));

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission));

        // Step 1: Manager approves Report 1
        ReviewFinancialReportRequest appReq = new ReviewFinancialReportRequest();
        appReq.setStatus(FinancialReportReviewStatus.APPROVED);
        researchService.reviewReport(projectId, taskId, "rep-1", appReq);

        assertThat(rep1.getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);

        // Step 2: Manager requests changes on Report 2
        ReviewFinancialReportRequest crReq = new ReviewFinancialReportRequest();
        crReq.setStatus(FinancialReportReviewStatus.CHANGES_REQUESTED);
        crReq.setReason("Re-extract balance sheet");
        researchService.reviewReport(projectId, taskId, "rep-2", crReq);

        assertThat(rep2.getReviewStatus()).isEqualTo(FinancialReportReviewStatus.CHANGES_REQUESTED);
        // CRITICAL: Even though Report 2 has CHANGES_REQUESTED, Report 3 is still PENDING -> parent remains IN_REVIEW
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);

        // Step 3: Manager approves Report 3
        researchService.reviewReport(projectId, taskId, "rep-3", appReq);

        assertThat(rep3.getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);
        // Now 0 pending remain -> parent aggregates to CHANGES_REQUESTED
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.CHANGES_REQUESTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.CHANGES_REQUESTED);
        assertThat(submission.getReviewComment()).contains("Report 2: Re-extract balance sheet");
    }

    @Test
    @DisplayName("Required Resubmission Extension: 3-report case A=APPROVED, B=CR, C=CR -> resubmit B & C -> approve B -> approve C")
    void test7_ThreeReportResubmissionExtension() {
        FinancialReportEntry repA = buildReport("rep-a", "Report A", FinancialReportReviewStatus.APPROVED);
        FinancialReportEntry repB = buildReport("rep-b", "Report B", FinancialReportReviewStatus.CHANGES_REQUESTED);
        FinancialReportEntry repC = buildReport("rep-c", "Report C", FinancialReportReviewStatus.CHANGES_REQUESTED);

        FinancialMetric metricB = FinancialMetric.builder()
                .id("m-b")
                .source(MetricSource.builder().reportEntryId("rep-b").build())
                .verificationStatus(MetricVerificationStatus.VERIFIED)
                .build();
        FinancialMetric metricC = FinancialMetric.builder()
                .id("m-c")
                .source(MetricSource.builder().reportEntryId("rep-c").build())
                .verificationStatus(MetricVerificationStatus.VERIFIED)
                .build();

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.CHANGES_REQUESTED)
                .reports(new ArrayList<>(List.of(repA, repB, repC)))
                .metrics(new ArrayList<>(List.of(metricB, metricC)))
                .build();

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        // Staff resubmits B and C
        researchService.submitForReview(projectId, taskId, staffId, List.of("rep-b", "rep-c"));

        assertThat(repA.getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);
        assertThat(repB.getReviewStatus()).isEqualTo(FinancialReportReviewStatus.PENDING_REVIEW);
        assertThat(repC.getReviewStatus()).isEqualTo(FinancialReportReviewStatus.PENDING_REVIEW);
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.SUBMITTED);
        assertThat(research.getSubmittedReportIds()).containsExactlyInAnyOrder("rep-b", "rep-c");

        // Manager reviews
        submission.setTargetItemIdList(List.of("rep-b", "rep-c"));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission));

        ReviewFinancialReportRequest appReq = new ReviewFinancialReportRequest();
        appReq.setStatus(FinancialReportReviewStatus.APPROVED);

        // Manager approves B
        researchService.reviewReport(projectId, taskId, "rep-b", appReq);
        assertThat(repB.getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);
        // C is still pending -> parent stays SUBMITTED / IN_REVIEW
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);

        // Manager approves C
        researchService.reviewReport(projectId, taskId, "rep-c", appReq);
        assertThat(repC.getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);
        // All A, B, C are now APPROVED -> parent APPROVED / DONE
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.APPROVED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
    }

    @Test
    @DisplayName("Review Validation: Reviewing an already decided report in the same cycle throws exception")
    void test8_ReviewAlreadyDecidedReport_ThrowsException() {
        FinancialReportEntry rep1 = buildReport("rep-1", "Report 1", FinancialReportReviewStatus.APPROVED);

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.SUBMITTED)
                .submittedReportIds(new ArrayList<>(List.of("rep-1")))
                .reports(new ArrayList<>(List.of(rep1)))
                .metrics(new ArrayList<>())
                .build();

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission));

        ReviewFinancialReportRequest request = new ReviewFinancialReportRequest();
        request.setStatus(FinancialReportReviewStatus.APPROVED);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.reviewReport(projectId, taskId, "rep-1", request)
        );

        assertThat(ex.getMessage()).contains("This report has already been reviewed in the current review cycle.");
    }
}
