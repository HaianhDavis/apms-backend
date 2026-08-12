package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.SubmissionStatus;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.enums.RoleEvaluationReviewDecision;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RoleEvaluationApprovalServiceTest {

    @Mock
    private RoleEvaluationDraftRepository draftRepository;

    @Mock
    private ProjectTaskRepository taskRepository;

    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;

    @Mock
    private RoleEvaluationApprovalStrategy strategy;

    @Mock
    private RoleEvaluationAuthorityService authorityService;

    private RoleEvaluationApprovalService service;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private ProjectTaskSubmission submission;
    private ReviewRoleEvaluationRequest request;

    @BeforeEach
    void setUp() {
        service = new RoleEvaluationApprovalService(draftRepository, taskRepository, submissionRepository, List.of(strategy), authorityService);

        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setTaskId(100L);
        draft.setProjectId(10L);
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);
        draft.setEvaluatedRole(CompanyRole.PARTNER);

        task = new ProjectTask();
        task.setId(100L);
        com.apms.domain.project.Project project = new com.apms.domain.project.Project();
        project.setId(10L);
        task.setProject(project);

        submission = new ProjectTaskSubmission();
        submission.setId(200L);
        submission.setSubmissionType(SubmissionType.ROLE_EVALUATION);
        submission.setStatus(SubmissionStatus.IN_REVIEW);
        submission.setTargetEntityId("draft-1");

        request = new ReviewRoleEvaluationRequest();
    }

    @Test
    void reviewDraft_DelegatesToStrategyApprove() {
        request.setDecision(RoleEvaluationReviewDecision.APPROVE);
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(submissionRepository.findByProjectTask_Id(100L)).thenReturn(List.of(submission));
        when(strategy.supports(CompanyRole.PARTNER)).thenReturn(true);

        service.reviewDraft("draft-1", request, 1L, "key-1");

        verify(strategy).approve(draft, task, submission, request, 1L, "key-1");
    }

    @Test
    void reviewDraft_BlocksManagerAfterOwnerFinalized() {
        request.setDecision(RoleEvaluationReviewDecision.APPROVE);
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        doThrow(new com.apms.common.exception.BusinessValidationException(RoleEvaluationAuthorityService.MANAGER_LOCK_MESSAGE))
                .when(authorityService).assertManagerMayReview(draft);

        assertThrows(com.apms.common.exception.BusinessValidationException.class,
                () -> service.reviewDraft("draft-1", request, 1L, "key-1"));

        verifyNoInteractions(strategy);
    }

    @Test
    void reviewDraft_DelegatesToStrategyRequestRevision() {
        request.setDecision(RoleEvaluationReviewDecision.REQUEST_REVISION);
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(submissionRepository.findByProjectTask_Id(100L)).thenReturn(List.of(submission));
        when(strategy.supports(CompanyRole.PARTNER)).thenReturn(true);

        service.reviewDraft("draft-1", request, 1L, null);

        verify(strategy).requestRevision(draft, task, submission, request, 1L);
    }

    @Test
    void reviewDraft_DelegatesToStrategyReject() {
        request.setDecision(RoleEvaluationReviewDecision.REJECT);
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(submissionRepository.findByProjectTask_Id(100L)).thenReturn(List.of(submission));
        when(strategy.supports(CompanyRole.PARTNER)).thenReturn(true);

        service.reviewDraft("draft-1", request, 1L, null);

        verify(strategy).reject(draft, task, submission, request, 1L);
    }

    @Test
    void reviewDraft_ThrowsExceptionWhenStrategyNotFound() {
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(submissionRepository.findByProjectTask_Id(100L)).thenReturn(List.of(submission));
        when(strategy.supports(CompanyRole.PARTNER)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.reviewDraft("draft-1", request, 1L, "key-1"));
    }
}
