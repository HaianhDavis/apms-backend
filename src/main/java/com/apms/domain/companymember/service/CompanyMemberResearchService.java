package com.apms.domain.companymember.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.companymember.CompanyMemberResearchDraft;
import com.apms.domain.companymember.CompanyMemberResearchItem;
import com.apms.domain.companymember.dto.CompanyMemberResearchDraftRequest;
import com.apms.domain.companymember.dto.MemberImageUploadResponse;
import com.apms.domain.document.service.StorageService;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import com.apms.domain.companymember.dto.CompanyMemberResearchDraftResponse;
import com.apms.domain.companymember.dto.CompanyMemberResearchItemRequest;
import com.apms.domain.companymember.repository.CompanyMemberResearchDraftRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.dto.CreateProjectTaskSubmissionRequest;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.project.service.ProjectTaskSubmissionService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyMemberResearchService {

    private final CompanyMemberResearchDraftRepository draftRepository;
    private final ProjectTaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final CompanyProfileRepository profileRepository;
    private final CompanyProfileVersionRepository profileVersionRepository;
    private final ProjectTaskSubmissionService submissionService;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final AuditLogService auditLogService;
    private final StorageService storageService;

    @Transactional
    public CompanyMemberResearchDraftResponse saveDraft(Long projectId, Long taskId, CompanyMemberResearchDraftRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        ProjectTask task = validateTaskAndAccess(projectId, taskId, currentUser, true);

        CompanyMemberResearchDraft draft = draftRepository.findByTaskId(taskId)
                .orElseGet(() -> CompanyMemberResearchDraft.builder()
                        .projectId(projectId)
                        .taskId(taskId)
                        .companyProfileId(task.getProject().getTargetCompanyProfileId())
                        .createdByAccountId(currentUser.getId())
                        .build());

        if (draft.getSubmissionId() != null) {
            ProjectTaskSubmission submission = submissionRepository.findById(draft.getSubmissionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Submission not found"));
            if (submission.getStatus() == SubmissionStatus.IN_REVIEW || submission.getStatus() == SubmissionStatus.APPROVED) {
                throw new BusinessValidationException("Cannot edit draft while submission is in review or approved");
            }
        }

        List<CompanyMemberResearchItem> items = request.getMembers().stream()
                .map(this::mapItemRequestToItem)
                .collect(Collectors.toList());

        draft.setMembers(items);
        draft = draftRepository.save(draft);

        AuditAction action = draft.getId() == null ? AuditAction.COMPANY_MEMBER_RESEARCH_DRAFT_CREATED : AuditAction.COMPANY_MEMBER_RESEARCH_DRAFT_UPDATED;
        auditLogService.log(currentUser.getId(), action, "CompanyMemberResearchDraft", draft.getId(), "Draft saved for task " + taskId);

        return toResponse(draft);
    }

    public CompanyMemberResearchDraftResponse getDraft(Long projectId, Long taskId) {
        UserDetailsImpl currentUser = getCurrentUser();
        validateTaskAndAccess(projectId, taskId, currentUser, false);

        CompanyMemberResearchDraft draft = draftRepository.findByTaskId(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found for task: " + taskId));

        return toResponse(draft);
    }

    public MemberImageUploadResponse uploadImage(Long projectId, Long taskId, MultipartFile file) {
        UserDetailsImpl currentUser = getCurrentUser();
        validateTaskAndAccess(projectId, taskId, currentUser, true);

        long maxImageSizeBytes = 2 * 1024 * 1024; // 2 MB
        if (file == null || file.isEmpty()) {
            throw new BusinessValidationException("Image file cannot be empty");
        }
        if (file.getSize() > maxImageSizeBytes) {
            throw new BusinessValidationException("Image size exceeds limit of 2 MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("image/webp"))) {
            throw new BusinessValidationException("Only JPEG, PNG, and WebP images are allowed");
        }

        String filename = storageService.store(file);
        String imageUrl = String.format("/api/v1/projects/%d/tasks/%d/company-members/images/%s", projectId, taskId, filename);

        auditLogService.log(currentUser.getId(), AuditAction.COMPANY_MEMBER_RESEARCH_DRAFT_UPDATED, "CompanyMemberResearchDraft", null, "Image uploaded: " + filename);

        return MemberImageUploadResponse.builder()
                .imageUrl(imageUrl)
                .filename(filename)
                .build();
    }

    public Resource getImage(String filename) {
        try {
            return storageService.loadAsResource(filename);
        } catch (Exception e) {
            throw new ResourceNotFoundException("Image not found: " + filename);
        }
    }

    @Transactional
    public void submitDraft(Long projectId, Long taskId) {
        UserDetailsImpl currentUser = getCurrentUser();
        ProjectTask task = validateTaskAndAccess(projectId, taskId, currentUser, true);

        CompanyMemberResearchDraft draft = draftRepository.findByTaskId(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found for task: " + taskId));

        if (draft.getMembers() == null || draft.getMembers().isEmpty()) {
            throw new BusinessValidationException("Cannot submit an empty draft");
        }

        if (draft.getSubmissionId() != null) {
            ProjectTaskSubmission submission = submissionRepository.findById(draft.getSubmissionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Submission not found"));
            if (submission.getStatus() == SubmissionStatus.IN_REVIEW || submission.getStatus() == SubmissionStatus.APPROVED) {
                throw new BusinessValidationException("Draft is already submitted or approved");
            }
        }

        // Create or reuse submission using the existing submission service
        CreateProjectTaskSubmissionRequest submissionReq = new CreateProjectTaskSubmissionRequest();
        submissionReq.setSubmissionType(SubmissionType.COMPANY_MEMBER_RESEARCH);
        submissionReq.setTargetEntityType("CompanyMemberResearchDraft");
        submissionReq.setTargetEntityId(draft.getId());
        submissionReq.setNote("Submitting company member research for review");

        var submissionResponse = submissionService.submitTask(projectId, taskId, submissionReq);

        draft.setSubmissionId(submissionResponse.getId());
        draftRepository.save(draft);

        auditLogService.log(currentUser.getId(), AuditAction.COMPANY_MEMBER_RESEARCH_SUBMITTED, "CompanyMemberResearchDraft", draft.getId(), "Draft submitted");
    }

    @Transactional
    public void cancelDraftSubmission(String draftId) {
        if (!StringUtils.hasText(draftId)) return;
        draftRepository.findById(draftId).ifPresent(draft -> {
            draft.setSubmissionId(null);
            draftRepository.save(draft);
        });
    }

    /**
     * Called when a COMPANY_MEMBER_RESEARCH submission is approved.
     * Handled within ProjectTaskSubmissionService, or called by a listener/hook.
     * We'll provide this public method to be called by ProjectTaskSubmissionService.
     */
    @Transactional
    public void handleApproval(ProjectTaskSubmission submission, Long reviewerId, String reviewComment) {
        if (submission.getSubmissionType() != SubmissionType.COMPANY_MEMBER_RESEARCH) {
            return;
        }

        CompanyMemberResearchDraft draft = draftRepository.findById(submission.getTargetEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found: " + submission.getTargetEntityId()));

        CompanyProfile profile = resolveTargetProfile(draft, submission);
        draft.setCompanyProfileId(profile.getId());
        draftRepository.save(draft);

        List<CompanyProfile.CompanyMember> existingMembers = profile.getCompanyMembers();
        if (existingMembers == null) {
            existingMembers = new ArrayList<>();
            profile.setCompanyMembers(existingMembers);
        }

        int addedCount = 0;
        for (CompanyMemberResearchItem draftItem : draft.getMembers()) {
            if (!isDuplicate(existingMembers, draftItem)) {
                CompanyProfile.CompanyMember newMember = CompanyProfile.CompanyMember.builder()
                        .fullName(draftItem.getFullName())
                        .position(draftItem.getPosition())
                        .imageUrl(draftItem.getImageUrl())
                        .sourceUrl(draftItem.getSourceUrl())
                        .notes(draftItem.getNotes())
                        .researchedAt(LocalDateTime.now())
                        .researchedBy(draft.getCreatedByAccountId())
                        .taskId(draft.getTaskId())
                        .build();
                existingMembers.add(newMember);
                addedCount++;
            }
        }

        if (addedCount > 0) {
            profile.setVersion(incrementMinorVersion(profile.getVersion()));
            if (profile.getMetadata() == null) {
                profile.setMetadata(new CompanyProfile.Metadata());
            }
            profile.getMetadata().setLastModifiedBy(String.valueOf(reviewerId));
            profile.getMetadata().setUpdatedAt(LocalDateTime.now());

            profileRepository.save(profile);

            // Create profile version
            CompanyProfileVersion version = CompanyProfileVersion.builder()
                    .companyProfileId(profile.getId())
                    .companyId(profile.getCompanyId())
                    .version(profile.getVersion())
                    .createdFromProjectId(submission.getProject().getId())
                    .createdFromTaskId(submission.getProjectTask().getId())
                    .changeSummary("Approved company members from task " + submission.getProjectTask().getId())
                    .createdBy(reviewerId)
                    .createdAt(LocalDateTime.now())
                    .build();
            profileVersionRepository.save(version);

            auditLogService.log(reviewerId, AuditAction.COMPANY_PROFILE_MEMBERS_UPDATED, "CompanyProfile", profile.getId(), "Added " + addedCount + " members");
        }

        auditLogService.log(reviewerId, AuditAction.COMPANY_MEMBER_RESEARCH_APPROVED, "CompanyMemberResearchDraft", draft.getId(), "Draft approved");
    }

    private CompanyProfile resolveTargetProfile(CompanyMemberResearchDraft draft, ProjectTaskSubmission submission) {
        List<String> lookupKeys = new ArrayList<>();
        if (StringUtils.hasText(draft.getCompanyProfileId())) {
            lookupKeys.add(draft.getCompanyProfileId());
        }
        if (submission.getProject() != null && StringUtils.hasText(submission.getProject().getTargetCompanyProfileId())) {
            lookupKeys.add(submission.getProject().getTargetCompanyProfileId());
        }

        for (String key : lookupKeys) {
            Optional<CompanyProfile> byDocumentId = profileRepository.findById(key);
            if (byDocumentId.isPresent()) {
                return byDocumentId.get();
            }

            Optional<CompanyProfile> byCompanyId = profileRepository.findByCompanyId(key);
            if (byCompanyId.isPresent()) {
                return byCompanyId.get();
            }
        }

        if (submission.getProject() != null && submission.getProject().getId() != null) {
            List<CompanyProfile> profiles = profileRepository.findByProjectId(String.valueOf(submission.getProject().getId()));
            if (!profiles.isEmpty()) {
                return profiles.get(0);
            }
        }

        throw new ResourceNotFoundException("Company profile not found for company member research task: " + draft.getTaskId());
    }

    private boolean isDuplicate(List<CompanyProfile.CompanyMember> existing, CompanyMemberResearchItem draftItem) {
        String normalizedDraftName = normalize(draftItem.getFullName());
        String normalizedDraftPos = normalize(draftItem.getPosition());

        return existing.stream().anyMatch(e ->
                normalize(e.getFullName()).equals(normalizedDraftName) &&
                normalize(e.getPosition()).equals(normalizedDraftPos)
        );
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) return "";
        return value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private ProjectTask validateTaskAndAccess(Long projectId, Long taskId, UserDetailsImpl user, boolean requireWrite) {
        ProjectTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (!task.getProject().getId().equals(projectId)) {
            throw new BusinessValidationException("Task does not belong to project");
        }

        if (task.getTaskType() != TaskType.COMPANY_MEMBER_RESEARCH) {
            throw new BusinessValidationException("Task is not a COMPANY_MEMBER_RESEARCH task");
        }

        if (!projectRepository.existsByIdAndMembersAccountId(projectId, user.getId())) {
            throw new AccessDeniedException("User is not a member of this project");
        }

        if (requireWrite) {
            boolean isAssignedStaff = hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF) &&
                    task.getAssignedToAccount() != null && task.getAssignedToAccount().getId().equals(user.getId());
            boolean isManager = hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER) || hasRole(user, SystemRole.SYSTEM_ADMIN);

            if (!isAssignedStaff && !isManager) {
                throw new AccessDeniedException("Only the assigned staff or manager can edit this draft");
            }
            if (task.getStatus() != TaskStatus.TODO && task.getStatus() != TaskStatus.IN_PROGRESS) {
                 throw new BusinessValidationException("Task is not in a state that allows editing");
            }
        }

        return task;
    }

    private CompanyMemberResearchItem mapItemRequestToItem(CompanyMemberResearchItemRequest req) {
        String sourceUrl = StringUtils.hasText(req.getSourceUrl()) ? req.getSourceUrl().trim() : null;
        String imageUrl = StringUtils.hasText(req.getImageUrl()) ? req.getImageUrl().trim() : null;
        return CompanyMemberResearchItem.builder()
                .fullName(req.getFullName() != null ? req.getFullName().trim() : null)
                .position(req.getPosition() != null ? req.getPosition().trim() : null)
                .imageUrl(imageUrl)
                .sourceUrl(sourceUrl)
                .notes(req.getNotes() != null ? req.getNotes().trim() : null)
                .build();
    }

    private CompanyMemberResearchDraftResponse toResponse(CompanyMemberResearchDraft draft) {
        return CompanyMemberResearchDraftResponse.builder()
                .id(draft.getId())
                .projectId(draft.getProjectId())
                .taskId(draft.getTaskId())
                .companyProfileId(draft.getCompanyProfileId())
                .createdByAccountId(draft.getCreatedByAccountId())
                .submissionId(draft.getSubmissionId())
                .members(draft.getMembers())
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

    private String incrementMinorVersion(String currentVersion) {
        if (currentVersion == null || currentVersion.isEmpty()) return "1.1";
        try {
            String[] parts = currentVersion.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major + "." + (minor + 1);
        } catch (Exception e) {
            return currentVersion + ".1";
        }
    }
}
