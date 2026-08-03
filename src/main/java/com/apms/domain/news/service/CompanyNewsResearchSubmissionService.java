package com.apms.domain.news.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.news.dto.SubmitCompanyNewsResearchRequest;
import com.apms.domain.news.entity.CompanyNewsResearchDraft;
import com.apms.domain.news.entity.CompanyNewsResearchSubmissionPayload;
import com.apms.domain.news.enums.NewsDraftStatus;
import com.apms.domain.news.repository.CompanyNewsResearchDraftRepository;
import com.apms.domain.news.repository.CompanyNewsResearchSubmissionPayloadRepository;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.dto.CreateProjectTaskSubmissionRequest;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.service.ProjectTaskSubmissionService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyNewsResearchSubmissionService {

    private final CompanyNewsResearchDraftRepository draftRepository;
    private final CompanyNewsResearchSubmissionPayloadRepository payloadRepository;
    private final ProjectTaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ProjectTaskSubmissionService submissionService;
    private final AuditLogService auditLogService;

    @Transactional
    public void submitNewsResearch(Long projectId, Long taskId, SubmitCompanyNewsResearchRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        ProjectTask task = validateTaskAndAccess(projectId, taskId, currentUser);

        List<String> draftIds = request.getNewsDraftIds();
        if (draftIds == null || draftIds.isEmpty()) {
            throw new BusinessValidationException("Draft IDs list cannot be empty");
        }

        List<CompanyNewsResearchDraft> draftsToSubmit = draftIds.stream()
                .map(id -> draftRepository.findByIdAndTaskIdAndIsDeletedFalse(id, taskId)
                        .orElseThrow(() -> new ResourceNotFoundException("Draft not found: " + id)))
                .toList();

        for (CompanyNewsResearchDraft draft : draftsToSubmit) {
            if (draft.getReviewStatus() != NewsDraftStatus.DRAFT) {
                throw new BusinessValidationException("Draft " + draft.getId() + " is not in DRAFT status");
            }
        }

        CompanyNewsResearchSubmissionPayload payload = CompanyNewsResearchSubmissionPayload.builder()
                .projectId(projectId)
                .taskId(taskId)
                .targetCompanyProfileId(task.getTargetCompanyProfileId())
                .newsDraftIds(draftIds)
                .build();
        payload = payloadRepository.save(payload);

        CreateProjectTaskSubmissionRequest submissionReq = new CreateProjectTaskSubmissionRequest();
        submissionReq.setSubmissionType(SubmissionType.COMPANY_NEWS_RESEARCH);
        submissionReq.setTargetEntityType("CompanyNewsResearchSubmissionPayload");
        submissionReq.setTargetEntityId(payload.getId());
        submissionReq.setNote("Submitting company news research drafts for review");

        var submissionResponse = submissionService.submitTask(projectId, taskId, submissionReq);

        payload.setSubmissionId(submissionResponse.getId());
        payloadRepository.save(payload);

        for (CompanyNewsResearchDraft draft : draftsToSubmit) {
            draft.setReviewStatus(NewsDraftStatus.SUBMITTED);
        }
        draftRepository.saveAll(draftsToSubmit);

        auditLogService.log(currentUser.getId(), AuditAction.COMPANY_NEWS_RESEARCH_SUBMITTED, "CompanyNewsResearchSubmissionPayload", payload.getId(), "News research submitted");
    }

    private ProjectTask validateTaskAndAccess(Long projectId, Long taskId, UserDetailsImpl user) {
        ProjectTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (!task.getProject().getId().equals(projectId)) {
            throw new BusinessValidationException("Task does not belong to project");
        }

        if (task.getTaskType() != TaskType.COMPANY_NEWS_RESEARCH) {
            throw new BusinessValidationException("Task is not a COMPANY_NEWS_RESEARCH task");
        }

        if (!projectRepository.existsByIdAndMembersAccountId(projectId, user.getId())) {
            throw new AccessDeniedException("User is not a member of this project");
        }

        boolean isAssignedStaff = hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF) &&
                task.getAssignedToAccount() != null && task.getAssignedToAccount().getId().equals(user.getId());

        if (!isAssignedStaff) {
            throw new AccessDeniedException("Only the assigned staff can submit drafts");
        }

        if (task.getStatus() != TaskStatus.TODO && task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new BusinessValidationException("Task is not in a state that allows submission");
        }

        return task;
    }

    private UserDetailsImpl getCurrentUser() {
        if (SecurityContextHolder.getContext().getAuthentication() == null ||
                !(SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof UserDetailsImpl)) {
            throw new AccessDeniedException("Unauthorized");
        }
        return (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        return user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role.name()));
    }
}
