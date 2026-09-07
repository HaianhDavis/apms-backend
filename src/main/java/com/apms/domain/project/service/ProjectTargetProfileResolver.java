package com.apms.domain.project.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;

@Service
@RequiredArgsConstructor
public class ProjectTargetProfileResolver {

    private final ProjectRepository projectRepository;
    private final ProjectTaskRepository projectTaskRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final CompanyProfileRepository companyProfileRepository;

    @Transactional
    public String resolveForPartnerContractTask(Long projectId, Long taskId, boolean persistLinkage) {
        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + taskId));

        if (!task.getProject().getId().equals(projectId)) {
            throw new BusinessValidationException("Task does not belong to the specified project");
        }
        if (task.getTaskType() != TaskType.PARTNER_CONTRACT_COLLECTION) {
            throw new BusinessValidationException("Task is not a PARTNER_CONTRACT_COLLECTION task");
        }

        Project project = task.getProject();

        String resolved = normalizeExistingProfileId(task.getTargetCompanyProfileId()).orElse(null);
        if (!StringUtils.hasText(resolved)) {
            resolved = normalizeExistingProfileId(project.getTargetCompanyProfileId()).orElse(null);
        }
        if (!StringUtils.hasText(resolved)) {
            resolved = resolveFromApprovedCandidate(project).orElse(null);
        }

        if (!StringUtils.hasText(resolved)) {
            throw new BusinessValidationException("Partner profile is not available yet. Complete Company Data Preparation and obtain Manager approval before submitting partner contracts.");
        }

        if (persistLinkage) {
            boolean projectChanged = !resolved.equals(project.getTargetCompanyProfileId());
            boolean taskChanged = !resolved.equals(task.getTargetCompanyProfileId());

            if (projectChanged) {
                project.setTargetCompanyProfileId(resolved);
                projectRepository.save(project);
            }
            if (taskChanged) {
                task.setTargetCompanyProfileId(resolved);
                projectTaskRepository.save(task);
            }
        }

        return resolved;
    }

    public String resolveTargetProfileId(Project project) {
        String resolved = normalizeExistingProfileId(project.getTargetCompanyProfileId()).orElse(null);
        if (!StringUtils.hasText(resolved)) {
            resolved = resolveFromApprovedCandidate(project).orElse(null);
        }
        return resolved;
    }

    @Transactional
    public void backfillPartnerContractTasks(Project project, String targetCompanyProfileId) {
        String resolved = normalizeExistingProfileId(targetCompanyProfileId)
                .orElseThrow(() -> new BusinessValidationException("Target CompanyProfile not found: " + targetCompanyProfileId));
        projectTaskRepository.findByProjectIdAndTaskTypeAndTargetCompanyProfileIdIsNull(project.getId(), TaskType.PARTNER_CONTRACT_COLLECTION)
                .forEach(task -> {
                    task.setTargetCompanyProfileId(resolved);
                    projectTaskRepository.save(task);
                });
    }

    private java.util.Optional<String> resolveFromApprovedCandidate(Project project) {
        LinkedHashSet<String> profileIds = new LinkedHashSet<>();
        for (CompanyCandidate candidate : candidateRepository.findAllByProjectIdAndStatus(String.valueOf(project.getId()), CandidateStatus.APPROVED)) {
            if (candidate.getLifecycle() != null && StringUtils.hasText(candidate.getLifecycle().getConvertedCompanyProfileId())) {
                normalizeExistingProfileId(candidate.getLifecycle().getConvertedCompanyProfileId())
                        .ifPresent(profileIds::add);
            }
        }
        return profileIds.size() == 1
                ? java.util.Optional.of(profileIds.iterator().next())
                : java.util.Optional.empty();
    }

    public java.util.Optional<String> normalizeExistingProfileId(String profileIdOrCompanyId) {
        if (!StringUtils.hasText(profileIdOrCompanyId)) {
            return java.util.Optional.empty();
        }
        String value = profileIdOrCompanyId.trim();
        CompanyProfile profile = companyProfileRepository.findByCompanyId(value)
                .or(() -> companyProfileRepository.findById(value))
                .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                .orElse(null);
        return profile != null && StringUtils.hasText(profile.getCompanyId())
                ? java.util.Optional.of(profile.getCompanyId())
                : java.util.Optional.empty();
    }

    @Transactional
    public CompanyProfile getOrCreateProjectProfileShell(Project project, Long managerId) {
        if (project == null) {
            return null;
        }

        // 1. If project already has targetCompanyProfileId, verify and return it
        if (StringUtils.hasText(project.getTargetCompanyProfileId())) {
            String existingId = project.getTargetCompanyProfileId().trim();
            java.util.Optional<CompanyProfile> existing = companyProfileRepository.findByCompanyId(existingId)
                    .or(() -> companyProfileRepository.findById(existingId))
                    .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()));
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // 2. Check by project linkage in sourceRefs
        if (project.getId() != null) {
            java.util.List<CompanyProfile> byProject = companyProfileRepository.findByProjectId(String.valueOf(project.getId()));
            if (!byProject.isEmpty()) {
                CompanyProfile shell = byProject.get(0);
                if (!shell.getCompanyId().equals(project.getTargetCompanyProfileId())) {
                    project.setTargetCompanyProfileId(shell.getCompanyId());
                    projectRepository.save(project);
                }
                return shell;
            }
        }

        // 3. Check by Tax Code if provided
        if (StringUtils.hasText(project.getTargetCompanyTaxCode())) {
            String normTaxCode = project.getTargetCompanyTaxCode().replaceAll("[\\s\\-]", "").trim();
            java.util.Optional<CompanyProfile> byTax = companyProfileRepository.findByIdentityTaxCode(normTaxCode)
                    .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()));
            if (byTax.isPresent()) {
                CompanyProfile existing = byTax.get();
                if (existing.getSourceRefs() == null) {
                    existing.setSourceRefs(new CompanyProfile.SourceRefs());
                }
                if (project.getId() != null) {
                    existing.getSourceRefs().getProjectIds().add(String.valueOf(project.getId()));
                }
                existing = companyProfileRepository.save(existing);
                project.setTargetCompanyProfileId(existing.getCompanyId());
                projectRepository.save(project);
                return existing;
            }
        }

        // 4. Create new shell with authoritative project identity
        String newCompanyId = java.util.UUID.randomUUID().toString();
        CompanyProfile.Identity identity = CompanyProfile.Identity.builder()
                .legalName(project.getTargetCompanyName())
                .taxCode(StringUtils.hasText(project.getTargetCompanyTaxCode()) ? project.getTargetCompanyTaxCode().replaceAll("[\\s\\-]", "").trim() : null)
                .build();

        Long responsibleManager = managerId;
        if (responsibleManager == null && project.getCreatedById() != null) {
            responsibleManager = project.getCreatedById();
        }

        CompanyProfile shell = CompanyProfile.builder()
                .companyId(newCompanyId)
                .identity(identity)
                .reviewStatus("UNVERIFIED")
                .isHidden(true)
                .majorVersion(1)
                .revision(0)
                .version("1.00")
                .responsibleManagerId(responsibleManager)
                .metadata(CompanyProfile.Metadata.builder()
                        .createdBy(responsibleManager != null ? String.valueOf(responsibleManager) : "SYSTEM")
                        .createdAt(java.time.LocalDateTime.now())
                        .updatedAt(java.time.LocalDateTime.now())
                        .build())
                .build();

        if (shell.getSourceRefs() == null) {
            shell.setSourceRefs(new CompanyProfile.SourceRefs());
        }
        if (project.getId() != null) {
            shell.getSourceRefs().getProjectIds().add(String.valueOf(project.getId()));
        }

        shell = companyProfileRepository.save(shell);

        project.setTargetCompanyProfileId(shell.getCompanyId());
        projectRepository.save(project);

        return shell;
    }
}
