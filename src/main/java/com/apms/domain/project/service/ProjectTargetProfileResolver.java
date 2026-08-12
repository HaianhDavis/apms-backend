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

    private java.util.Optional<String> normalizeExistingProfileId(String profileIdOrCompanyId) {
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
}
