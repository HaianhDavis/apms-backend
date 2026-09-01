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
class FinancialResearchRecallTest {

    @Mock
    private FinancialResearchRepository researchRepository;
    @Mock
    private ProjectTaskRepository projectTaskRepository;
    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private FinancialResearchService researchService;

    private final Long projectId = 1L;
    private final Long taskId = 100L;
    private final Long staffId = 42L;

    private Project project;
    private ProjectTask task;
    private Account staffAccount;
    private UserDetailsImpl userDetails;

    @BeforeEach
    void setUp() {
        project = Project.builder().id(projectId).projectName("Test Project").build();
        staffAccount = Account.builder().id(staffId).email("staff@apms.com").passwordHash("hash").build();
        task = ProjectTask.builder()
                .id(taskId)
                .project(project)
                .assignedToAccount(staffAccount)
                .status(TaskStatus.IN_REVIEW)
                .build();

        userDetails = new UserDetailsImpl(
                staffId,
                "staff@apms.com",
                "hash",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")),
                true
        );

        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getPrincipal()).thenReturn(userDetails);
        SecurityContext secContext = mock(SecurityContext.class);
        lenient().when(secContext.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(secContext);
    }

    @Test
    @DisplayName("Staff can recall submission when all reports in current submission are PENDING_REVIEW")
    void recallSubmission_Success_WhenAllReportsPendingReview() {
        FinancialReportEntry rep1 = FinancialReportEntry.builder()
                .id("rep-1")
                .title("Report Q1")
                .reviewStatus(FinancialReportReviewStatus.PENDING_REVIEW)
                .build();
        FinancialReportEntry rep2 = FinancialReportEntry.builder()
                .id("rep-2")
                .title("Report Q2")
                .reviewStatus(FinancialReportReviewStatus.PENDING_REVIEW)
                .build();

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.SUBMITTED)
                .submittedReportIds(new ArrayList<>(List.of("rep-1", "rep-2")))
                .reports(new ArrayList<>(List.of(rep1, rep2)))
                .metrics(new ArrayList<>())
                .build();

        ProjectTaskSubmission submission = ProjectTaskSubmission.builder()
                .id(999L)
                .projectTask(task)
                .project(project)
                .status(SubmissionStatus.IN_REVIEW)
                .submittedByAccount(staffAccount)
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission));
        when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        FinancialResearchResponse response = researchService.recallSubmission(projectId, taskId);

        assertThat(response).isNotNull();
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.WITHDRAWN);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.DRAFT);
        assertThat(research.getSubmittedReportIds()).isEmpty();
        assertThat(rep1.getReviewStatus()).isNull();
        assertThat(rep2.getReviewStatus()).isNull();

        verify(auditLogService).log(eq(staffId), eq(AuditAction.FINANCIAL_RESEARCH_SUBMISSION_RECALLED), eq("ProjectTask"), eq(String.valueOf(taskId)), anyString());
    }

    @Test
    @DisplayName("Recall is rejected if Manager has already APPROVED any report in the submission")
    void recallSubmission_Fails_WhenAnyReportApproved() {
        FinancialReportEntry rep1 = FinancialReportEntry.builder()
                .id("rep-1")
                .title("Report Q1")
                .reviewStatus(FinancialReportReviewStatus.APPROVED)
                .build();
        FinancialReportEntry rep2 = FinancialReportEntry.builder()
                .id("rep-2")
                .title("Report Q2")
                .reviewStatus(FinancialReportReviewStatus.PENDING_REVIEW)
                .build();

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.SUBMITTED)
                .submittedReportIds(new ArrayList<>(List.of("rep-1", "rep-2")))
                .reports(new ArrayList<>(List.of(rep1, rep2)))
                .metrics(new ArrayList<>())
                .build();

        ProjectTaskSubmission submission = ProjectTaskSubmission.builder()
                .id(999L)
                .projectTask(task)
                .project(project)
                .status(SubmissionStatus.IN_REVIEW)
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.recallSubmission(projectId, taskId)
        );

        assertThat(ex.getMessage()).contains("This submission can no longer be recalled because Manager review has already started");
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.IN_REVIEW);
    }

    @Test
    @DisplayName("Recall is rejected if Manager has already REQUESTED CHANGES for any report in the submission")
    void recallSubmission_Fails_WhenAnyReportChangesRequested() {
        FinancialReportEntry rep1 = FinancialReportEntry.builder()
                .id("rep-1")
                .title("Report Q1")
                .reviewStatus(FinancialReportReviewStatus.CHANGES_REQUESTED)
                .reviewComment("Please re-extract")
                .build();

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.SUBMITTED)
                .submittedReportIds(new ArrayList<>(List.of("rep-1")))
                .reports(new ArrayList<>(List.of(rep1)))
                .metrics(new ArrayList<>())
                .build();

        ProjectTaskSubmission submission = ProjectTaskSubmission.builder()
                .id(999L)
                .projectTask(task)
                .project(project)
                .status(SubmissionStatus.IN_REVIEW)
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.recallSubmission(projectId, taskId)
        );

        assertThat(ex.getMessage()).contains("This submission can no longer be recalled because Manager review has already started");
    }

    @Test
    @DisplayName("Resubmission recall restores CHANGES_REQUESTED for resubmitted report and leaves previously approved report intact")
    void recallSubmission_Resubmission_RestoresChangesRequested() {
        FinancialReportEntry repX = FinancialReportEntry.builder()
                .id("rep-x")
                .title("Report Q1 (Approved earlier)")
                .reviewStatus(FinancialReportReviewStatus.APPROVED)
                .build();

        FinancialReportEntry repY = FinancialReportEntry.builder()
                .id("rep-y")
                .title("Report Q2 (Resubmitted)")
                .reviewStatus(FinancialReportReviewStatus.PENDING_REVIEW)
                .reviewComment("Manager prior feedback: Fix liabilities")
                .build();

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.SUBMITTED)
                .submittedReportIds(new ArrayList<>(List.of("rep-y"))) // Only rep-y was submitted in this round
                .reports(new ArrayList<>(List.of(repX, repY)))
                .metrics(new ArrayList<>())
                .build();

        ProjectTaskSubmission submission2 = ProjectTaskSubmission.builder()
                .id(1002L)
                .projectTask(task)
                .project(project)
                .status(SubmissionStatus.IN_REVIEW)
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission2));
        when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        FinancialResearchResponse response = researchService.recallSubmission(projectId, taskId);

        assertThat(response).isNotNull();
        assertThat(submission2.getStatus()).isEqualTo(SubmissionStatus.WITHDRAWN);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        // repX remains APPROVED
        assertThat(repX.getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);
        // repY returns to CHANGES_REQUESTED with feedback preserved
        assertThat(repY.getReviewStatus()).isEqualTo(FinancialReportReviewStatus.CHANGES_REQUESTED);
        assertThat(repY.getReviewComment()).isEqualTo("Manager prior feedback: Fix liabilities");
        // Research status returns to CHANGES_REQUESTED because repY has CHANGES_REQUESTED
        assertThat(research.getStatus()).isEqualTo(FinancialResearchStatus.CHANGES_REQUESTED);
    }

    @Test
    @DisplayName("Manager reviewReport is rejected when submission has been WITHDRAWN / no active submission")
    void reviewReport_Fails_WhenSubmissionWithdrawn() {
        FinancialReportEntry rep1 = FinancialReportEntry.builder()
                .id("rep-1")
                .title("Report Q1")
                .reviewStatus(FinancialReportReviewStatus.PENDING_REVIEW)
                .extractionStatus(ExtractionStatus.EXTRACTED)
                .build();

        FinancialResearch research = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.SUBMITTED)
                .submittedReportIds(new ArrayList<>(List.of("rep-1")))
                .reports(new ArrayList<>(List.of(rep1)))
                .metrics(new ArrayList<>())
                .build();

        // Submission is WITHDRAWN
        ProjectTaskSubmission submission = ProjectTaskSubmission.builder()
                .id(999L)
                .projectTask(task)
                .project(project)
                .status(SubmissionStatus.WITHDRAWN)
                .build();

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(submission));

        ReviewFinancialReportRequest request = new ReviewFinancialReportRequest();
        request.setStatus(FinancialReportReviewStatus.APPROVED);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.reviewReport(projectId, taskId, "rep-1", request)
        );

        assertThat(ex.getMessage()).contains("This submission is no longer available for review");
    }
}