package com.apms.domain.profile.service;

import com.apms.common.enums.ProjectStatus;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompanyProfileOfficialEvaluator {

    private final ProjectRepository projectRepository;

    /**
     * Determines whether a CompanyProfile document represents an authoritative, official existing company.
     *
     * <p>Business Rules:
     * <ul>
     *   <li>A profile is NOT official if deleted.</li>
     *   <li>A profile originating from or linked to projects counts as an existing company
     *       only when at least one originating/linked project reached {@link ProjectStatus#COMPLETED}.
     *       In-progress (DRAFT, ACTIVE) or abandoned (CLOSED, CANCELLED) research does NOT make a shell official.</li>
     *   <li>If an earlier completed project exists, a subsequent closed/cancelled project does NOT revoke official status.</li>
     *   <li>Visibility (PUBLISHED vs HIDDEN) does NOT gate existence: an official completed profile may be HIDDEN.</li>
     *   <li>Profiles without linked projects (e.g. initial seeds or imports) are official if reviewStatus is APPROVED or VERIFIED.</li>
     * </ul>
     */
    public boolean isOfficial(CompanyProfile profile) {
        if (profile == null || Boolean.TRUE.equals(profile.getIsDeleted())) {
            return false;
        }

        // 1. Gather all linked project IDs from sourceRefs and targetCompanyProfileId
        Set<Long> linkedProjectIds = new HashSet<>();
        if (profile.getSourceRefs() != null && profile.getSourceRefs().getProjectIds() != null) {
            for (String pidStr : profile.getSourceRefs().getProjectIds()) {
                try {
                    linkedProjectIds.add(Long.parseLong(pidStr.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (StringUtils.hasText(profile.getCompanyId())) {
            List<Project> byCompanyId = projectRepository.findByTargetCompanyProfileId(profile.getCompanyId().trim());
            for (Project p : byCompanyId) {
                if (p.getId() != null) linkedProjectIds.add(p.getId());
            }
        }
        if (StringUtils.hasText(profile.getId())) {
            List<Project> byId = projectRepository.findByTargetCompanyProfileId(profile.getId().trim());
            for (Project p : byId) {
                if (p.getId() != null) linkedProjectIds.add(p.getId());
            }
        }

        // 2. Provenance check: If projects are linked, at least one MUST have reached COMPLETED
        if (!linkedProjectIds.isEmpty()) {
            List<Project> projects = projectRepository.findAllById(linkedProjectIds);
            boolean hasCompletedProject = projects.stream().anyMatch(p -> p.getStatus() == ProjectStatus.COMPLETED);
            if (hasCompletedProject) {
                return true;
            }
            // All linked projects are non-completed (e.g., DRAFT, ACTIVE, CLOSED, CANCELLED).
            // This is a temporary or abandoned shell, not an official company.
            return false;
        }

        // 3. Profiles without linked projects (seed data, direct imports)
        String reviewStatus = profile.getReviewStatus();
        return "APPROVED".equalsIgnoreCase(reviewStatus) || "VERIFIED".equalsIgnoreCase(reviewStatus);
    }
}
