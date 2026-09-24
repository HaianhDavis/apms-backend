package com.apms.common.security;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.SystemRole;
import com.apms.domain.ai.AiExtractionCache;
import com.apms.domain.ai.repository.mongo.AiExtractionCacheRepository;
import com.apms.domain.document.ImportJob;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Spring Security Expression component that enforces the BUSINESS_DEVELOPMENT_STAFF
 * data-scope rule at the backend.
 *
 * Business rule:
 *   A Staff member may access a Company if:
 *     A) it is the APMS Owner Company (global access for every Staff member), OR
 *     B) the company is the target of a Project the Staff member is assigned to.
 *
 * All other roles (SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER)
 * keep their current system-wide access and are never restricted by this guard.
 *
 * Usage in controllers:
 *   @PreAuthorize("@companyScope.canAccessCompany(#companyId)")
 *   @PreAuthorize("@companyScope.canAccessProject(#projectId)")
 *   @PreAuthorize("@companyScope.canAccessEvaluation(#evaluationId)")
 *   @PreAuthorize("@companyScope.canAccessImportJob(#importJobId)")
 *   @PreAuthorize("@companyScope.canAccessProposal(#proposalId)")
 *   @PreAuthorize("@companyScope.canAccessExtraction(#extractionId)")
 */
@Component("companyScope")
@RequiredArgsConstructor
public class StaffCompanyScopeEvaluator {

    private static final List<ProjectStatus> ALL_PROJECT_STATUSES = Arrays.asList(ProjectStatus.values());

    private final OwnerOrganizationService ownerOrganizationService;
    private final CompanyProfileRepository companyProfileRepository;
    private final ProjectRepository projectRepository;
    private final com.apms.domain.project.repository.sql.ProjectMemberRepository projectMemberRepository;
    private final ImportJobRepository importJobRepository;
    private final CompanyProfileUpdateProposalRepository proposalRepository;
    private final AiExtractionCacheRepository extractionCacheRepository;
    private final com.apms.domain.monitoring.repository.CompanyMonitoringAssignmentRepository monitoringAssignmentRepository;

    /**
     * Authoritative project-scoped read evaluator.
     * Evaluates ONLY:
     * 1. Project exists.
     * 2. The account is a ProjectMember of that project.
     * 3. The requested company profile resolves to the target company profile of that project.
     *
     * Does NOT check roles (no 'if role != STAFF return true').
     * Global Manager/Owner/Admin authorization must continue through existing role/access rules.
     */
    public boolean canReadCompanyProfileFromProject(Long accountId, Long projectId, String requestedProfileId) {
        if (accountId == null || projectId == null || !StringUtils.hasText(requestedProfileId)) {
            return false;
        }

        // 1. Verify project exists
        com.apms.domain.project.Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return false;
        }

        // 2. Verify account is a ProjectMember of that project
        boolean isMember = projectMemberRepository.existsByProject_IdAndAccount_Id(projectId, accountId);
        if (!isMember) {
            return false;
        }

        // 3. Verify requested profile is the target company profile of that project
        String targetCompanyProfileId = project.getTargetCompanyProfileId();
        if (!StringUtils.hasText(targetCompanyProfileId)) {
            return false;
        }

        // Resolve requested profile to canonical entity
        CompanyProfile requestedProfile = resolveProfile(requestedProfileId);
        if (requestedProfile == null) {
            return false;
        }

        // Resolve project target company profile to canonical entity
        CompanyProfile targetProfile = resolveProfile(targetCompanyProfileId);
        if (targetProfile == null) {
            // Fallback if target company profile cannot be resolved in MongoDB: compare raw identifier
            return targetCompanyProfileId.equals(requestedProfile.getId())
                    || targetCompanyProfileId.equals(requestedProfile.getCompanyId())
                    || targetCompanyProfileId.equals(requestedProfileId);
        }

        // Canonical comparison: compare canonical identifier (Mongo ID or universal companyId)
        if (requestedProfile.getId() != null && requestedProfile.getId().equals(targetProfile.getId())) {
            return true;
        }
        if (requestedProfile.getCompanyId() != null && requestedProfile.getCompanyId().equals(targetProfile.getCompanyId())) {
            return true;
        }

        return false;
    }

    // ─────────────────────────────────────────────
    // Company-level guards
    // ─────────────────────────────────────────────

    /**
     * Returns true if the current user may read the given company resource.
     * Non-Staff roles pass. Staff must be able to access the owner company
     * OR a company that is the target of an assigned project.
     *
     * @param companyIdOrProfileId Mongo companyId OR CompanyProfile _id
     */
    public boolean canAccessCompany(String companyIdOrProfileId) {
        UserDetailsImpl user = currentUser();
        if (user == null) return false;
        if (!isStaff(user)) return true;
        if (!StringUtils.hasText(companyIdOrProfileId)) return false;

        if (ownerOrganizationService.isOwnerCompany(companyIdOrProfileId)) return true;

        CompanyProfile profile = resolveProfile(companyIdOrProfileId);
        if (profile == null) return false;

        return isCompanyInScope(profile.getId(), user.getId());
    }

    /**
     * Company-scope rule applied to an already-resolved companyId.
     */
    public boolean canAccessCompanyId(String companyId, UserDetailsImpl user) {
        if (user == null) return false;
        if (!isStaff(user)) return true;
        if (!StringUtils.hasText(companyId)) return false;
        if (ownerOrganizationService.isOwnerCompany(companyId)) return true;
        CompanyProfile profile = resolveProfile(companyId);
        if (profile == null) return false;
        return isCompanyInScope(profile.getId(), user.getId());
    }

    /**
     * Authoritative check for whether the current user can directly edit/manage the CompanyProfile.
     * Allowed ONLY for:
     * - SYSTEM_ADMIN
     * - BUSINESS_DEVELOPMENT_MANAGER where profile.getResponsibleManagerId() == user.getId()
     * Denied for:
     * - STAFF
     * - OWNER
     * - Unassigned profiles (responsibleManagerId == null) for any Manager
     * - Managers who are not the assigned responsible manager
     */
    public boolean canManageCompanyProfile(String companyIdOrProfileId) {
        UserDetailsImpl user = currentUser();
        if (user == null || !StringUtils.hasText(companyIdOrProfileId)) return false;
        if (hasRole(user, SystemRole.SYSTEM_ADMIN)) return true;
        if (!hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) return false;

        CompanyProfile profile = resolveProfile(companyIdOrProfileId);
        if (profile == null) return false;
        if (Boolean.TRUE.equals(profile.getIsDeleted())) return false;

        return profile.getResponsibleManagerId() != null && profile.getResponsibleManagerId().equals(user.getId());
    }

    // ─────────────────────────────────────────────
    // Project-level guards
    // ─────────────────────────────────────────────

    /**
     * Staff may only access a Project they are assigned to.
     * All other roles keep their current access.
     */
    public boolean canAccessProject(Long projectId) {
        UserDetailsImpl user = currentUser();
        if (user == null || projectId == null) return false;
        if (!isStaff(user)) return true;
        return projectRepository.existsByIdAndMembersAccountId(projectId, user.getId());
    }

    // ─────────────────────────────────────────────
    // Derived-project guards (evaluation / import job / proposal / extraction)
    // ─────────────────────────────────────────────

    public boolean canAccessImportJob(Long importJobId) {
        UserDetailsImpl user = currentUser();
        if (user == null) return false;
        if (!isStaff(user)) return true;
        if (importJobId == null) return false;
        return importJobRepository.findById(importJobId)
                .map(ImportJob::getProjectId)
                .map(projectId -> projectRepository.existsByIdAndMembersAccountId(projectId, user.getId()))
                .orElse(false);
    }

    public boolean canAccessProposal(String proposalId) {
        UserDetailsImpl user = currentUser();
        if (user == null) return false;
        if (!isStaff(user)) return true;
        if (!StringUtils.hasText(proposalId)) return false;
        return proposalRepository.findById(proposalId)
                .map(proposal -> {
                    if (proposal.getSubmittedBy() != null && proposal.getSubmittedBy().equals(user.getId())) {
                        return true;
                    }
                    if (proposal.getProjectId() != null) {
                        return projectRepository.existsByIdAndMembersAccountId(proposal.getProjectId(), user.getId());
                    }
                    return false;
                })
                .orElse(false);
    }

    public boolean canAccessExtraction(String extractionId) {
        UserDetailsImpl user = currentUser();
        if (user == null) return false;
        if (!isStaff(user)) return true;
        if (!StringUtils.hasText(extractionId)) return false;
        return extractionCacheRepository.findById(extractionId)
                .map(AiExtractionCache::getImportJobId)
                .map(this::canAccessImportJobInternal)
                .orElse(false);
    }

    private boolean canAccessImportJobInternal(Long importJobId) {
        if (importJobId == null) return false;
        UserDetailsImpl user = currentUser();
        if (user == null) return false;
        return importJobRepository.findById(importJobId)
                .map(ImportJob::getProjectId)
                .map(projectId -> projectRepository.existsByIdAndMembersAccountId(projectId, user.getId()))
                .orElse(false);
    }

    // ─────────────────────────────────────────────
    // List-filtering support
    // ─────────────────────────────────────────────

    /**
     * Returns the set of companyIds the current user may access, or
     * {@code null} for non-Staff roles (meaning "no restriction").
     * For Staff: the Owner Company + every target company of assigned projects.
     */
    public Set<String> allowedCompanyIds() {
        UserDetailsImpl user = currentUser();
        if (user == null) return java.util.Collections.emptySet();
        if (!isStaff(user)) return null;

        Set<String> ids = new LinkedHashSet<>();
        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        if (ownerId != null) {
            ids.add(ownerId);
        }
        List<String> projectTargets = projectRepository.findTargetCompanyProfileIdsByMemberAccountId(user.getId());
        if (projectTargets != null) {
            ids.addAll(projectTargets);
        }
        return ids;
    }

    public boolean isCurrentUserStaff() {
        UserDetailsImpl user = currentUser();
        return user != null && isStaff(user);
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private CompanyProfile resolveProfile(String companyIdOrProfileId) {
        return companyProfileRepository.findByCompanyId(companyIdOrProfileId)
                .or(() -> companyProfileRepository.findById(companyIdOrProfileId))
                .orElse(null);
    }

    private boolean isCompanyInScope(String companyIdOrProfileId, Long accountId) {
        CompanyProfile profile = resolveProfile(companyIdOrProfileId);
        String compId = profile != null && profile.getCompanyId() != null ? profile.getCompanyId() : companyIdOrProfileId;
        String mongoId = profile != null && profile.getId() != null ? profile.getId() : companyIdOrProfileId;

        if (projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(compId, accountId, ALL_PROJECT_STATUSES)
                || (mongoId != null && projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(mongoId, accountId, ALL_PROJECT_STATUSES))) {
            return true;
        }
        return monitoringAssignmentRepository.existsByCompanyProfileIdAndAssignedStaffId(compId, accountId)
                || (mongoId != null && monitoringAssignmentRepository.existsByCompanyProfileIdAndAssignedStaffId(mongoId, accountId))
                || (companyIdOrProfileId != null && monitoringAssignmentRepository.existsByCompanyProfileIdAndAssignedStaffId(companyIdOrProfileId, accountId));
    }

    private boolean isStaff(UserDetailsImpl user) {
        return hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        String roleName = "ROLE_" + role.name();
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(roleName));
    }

    private UserDetailsImpl currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl)) return null;
        return (UserDetailsImpl) auth.getPrincipal();
    }
}
