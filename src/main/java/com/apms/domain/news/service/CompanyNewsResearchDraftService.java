package com.apms.domain.news.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.service.StorageService;
import com.apms.domain.news.dto.CompanyNewsResearchDraftResponse;
import com.apms.domain.news.dto.CreateNewsResearchDraftRequest;
import com.apms.domain.news.dto.NewsImageUploadResponse;
import com.apms.domain.news.dto.UpdateNewsResearchDraftRequest;
import com.apms.domain.news.entity.CompanyNewsResearchDraft;
import com.apms.domain.news.enums.NewsDraftStatus;
import com.apms.domain.news.repository.CompanyNewsResearchDraftRepository;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyNewsResearchDraftService {

    private final CompanyNewsResearchDraftRepository draftRepository;
    private final ProjectTaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final AuditLogService auditLogService;
    private final StorageService storageService;

    @Value("${apms.news.image.max-size-bytes:5242880}")
    private long maxImageSizeBytes;

    @Transactional
    public CompanyNewsResearchDraftResponse createDraft(Long projectId, Long taskId, CreateNewsResearchDraftRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        ProjectTask task = validateTaskAndAccess(projectId, taskId, currentUser, true);

        if (!StringUtils.hasText(request.getContent()) && !StringUtils.hasText(request.getSummary())) {
            throw new BusinessValidationException("At least one of content or summary must be non-blank");
        }

        CompanyNewsResearchDraft draft = CompanyNewsResearchDraft.builder()
                .projectId(projectId)
                .taskId(taskId)
                .targetCompanyProfileId(task.getTargetCompanyProfileId())
                .title(request.getTitle())
                .summary(request.getSummary())
                .content(request.getContent())
                .imageStorageKey(request.getImageStorageKey())
                .externalImageUrl(request.getExternalImageUrl())
                .sourceName(request.getSourceName())
                .sourceUrl(request.getSourceUrl())
                .author(request.getAuthor())
                .publishedAt(request.getPublishedAt())
                .capturedAt(LocalDateTime.now())
                .tags(request.getTags())
                .staffNotes(request.getStaffNotes())
                .createdByAccountId(currentUser.getId())
                .build();

        draft = draftRepository.save(draft);

        auditLogService.log(currentUser.getId(), AuditAction.COMPANY_NEWS_DRAFT_CREATED, "CompanyNewsResearchDraft", draft.getId(), "Draft created for task " + taskId);

        return toResponse(draft);
    }

    @Transactional
    public CompanyNewsResearchDraftResponse updateDraft(Long projectId, Long taskId, String draftId, UpdateNewsResearchDraftRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        validateTaskAndAccess(projectId, taskId, currentUser, true);

        CompanyNewsResearchDraft draft = draftRepository.findByIdAndTaskIdAndIsDeletedFalse(draftId, taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found"));

        if (draft.getReviewStatus() == NewsDraftStatus.SUBMITTED || draft.getReviewStatus() == NewsDraftStatus.APPROVED) {
            throw new BusinessValidationException("Cannot edit a submitted or approved draft");
        }

        if (request.getTitle() != null) draft.setTitle(request.getTitle());
        if (request.getSummary() != null) draft.setSummary(request.getSummary());
        if (request.getContent() != null) draft.setContent(request.getContent());
        if (request.getImageStorageKey() != null) draft.setImageStorageKey(request.getImageStorageKey());
        if (request.getExternalImageUrl() != null) draft.setExternalImageUrl(request.getExternalImageUrl());
        if (request.getSourceName() != null) draft.setSourceName(request.getSourceName());
        if (request.getSourceUrl() != null) draft.setSourceUrl(request.getSourceUrl());
        if (request.getAuthor() != null) draft.setAuthor(request.getAuthor());
        if (request.getPublishedAt() != null) draft.setPublishedAt(request.getPublishedAt());
        if (request.getTags() != null) draft.setTags(request.getTags());
        if (request.getStaffNotes() != null) draft.setStaffNotes(request.getStaffNotes());

        if (!StringUtils.hasText(draft.getContent()) && !StringUtils.hasText(draft.getSummary())) {
            throw new BusinessValidationException("At least one of content or summary must be non-blank");
        }

        draft = draftRepository.save(draft);
        auditLogService.log(currentUser.getId(), AuditAction.COMPANY_NEWS_DRAFT_UPDATED, "CompanyNewsResearchDraft", draft.getId(), "Draft updated");

        return toResponse(draft);
    }

    public List<CompanyNewsResearchDraftResponse> getDrafts(Long projectId, Long taskId) {
        UserDetailsImpl currentUser = getCurrentUser();
        validateTaskAndAccess(projectId, taskId, currentUser, false);

        return draftRepository.findByTaskIdAndIsDeletedFalse(taskId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public CompanyNewsResearchDraftResponse getDraft(Long projectId, Long taskId, String draftId) {
        UserDetailsImpl currentUser = getCurrentUser();
        validateTaskAndAccess(projectId, taskId, currentUser, false);

        CompanyNewsResearchDraft draft = draftRepository.findByIdAndTaskIdAndIsDeletedFalse(draftId, taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found"));

        return toResponse(draft);
    }

    @Transactional
    public void deleteDraft(Long projectId, Long taskId, String draftId) {
        UserDetailsImpl currentUser = getCurrentUser();
        validateTaskAndAccess(projectId, taskId, currentUser, true);

        CompanyNewsResearchDraft draft = draftRepository.findByIdAndTaskIdAndIsDeletedFalse(draftId, taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found"));

        if (draft.getReviewStatus() == NewsDraftStatus.SUBMITTED || draft.getReviewStatus() == NewsDraftStatus.APPROVED) {
            throw new BusinessValidationException("Cannot delete a submitted or approved draft");
        }

        draft.setDeleted(true);
        draftRepository.save(draft);

        auditLogService.log(currentUser.getId(), AuditAction.COMPANY_NEWS_DRAFT_DELETED, "CompanyNewsResearchDraft", draft.getId(), "Draft soft deleted");
    }

    public NewsImageUploadResponse uploadImage(Long projectId, Long taskId, MultipartFile file) {
        UserDetailsImpl currentUser = getCurrentUser();
        validateTaskAndAccess(projectId, taskId, currentUser, true);

        if (file.getSize() > maxImageSizeBytes) {
            throw new BusinessValidationException("Image size exceeds limit of " + maxImageSizeBytes + " bytes");
        }

        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("image/webp"))) {
            throw new BusinessValidationException("Only JPEG, PNG, and WebP images are allowed");
        }

        String storageKey = storageService.store(file);
        return NewsImageUploadResponse.builder().storageKey(storageKey).build();
    }

    private ProjectTask validateTaskAndAccess(Long projectId, Long taskId, UserDetailsImpl user, boolean requireWrite) {
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

        if (requireWrite) {
            boolean isAssignedStaff = hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF) &&
                    task.getAssignedToAccount() != null && task.getAssignedToAccount().getId().equals(user.getId());
            
            if (!isAssignedStaff) {
                throw new AccessDeniedException("Only the assigned staff can edit drafts or upload images");
            }
            if (task.getStatus() != TaskStatus.TODO && task.getStatus() != TaskStatus.IN_PROGRESS) {
                throw new BusinessValidationException("Task is not in a state that allows editing");
            }
        }

        return task;
    }

    private CompanyNewsResearchDraftResponse toResponse(CompanyNewsResearchDraft draft) {
        return CompanyNewsResearchDraftResponse.builder()
                .id(draft.getId())
                .projectId(draft.getProjectId())
                .taskId(draft.getTaskId())
                .targetCompanyProfileId(draft.getTargetCompanyProfileId())
                .title(draft.getTitle())
                .summary(draft.getSummary())
                .content(draft.getContent())
                .imageStorageKey(draft.getImageStorageKey())
                .externalImageUrl(draft.getExternalImageUrl())
                .sourceName(draft.getSourceName())
                .sourceUrl(draft.getSourceUrl())
                .author(draft.getAuthor())
                .publishedAt(draft.getPublishedAt())
                .capturedAt(draft.getCapturedAt())
                .tags(draft.getTags())
                .staffNotes(draft.getStaffNotes())
                .reviewStatus(draft.getReviewStatus())
                .createdByAccountId(draft.getCreatedByAccountId())
                .createdAt(draft.getCreatedAt())
                .updatedAt(draft.getUpdatedAt())
                .build();
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
