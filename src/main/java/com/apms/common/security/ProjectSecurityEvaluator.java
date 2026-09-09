package com.apms.common.security;

import com.apms.common.enums.SystemRole;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Spring Security Expression component for row-level authorization.
 *
 * Usage in controllers:
 *   @PreAuthorize("@projectSecurity.isMember(#projectId)")
 *   @PreAuthorize("@projectSecurity.isMemberOrOwner(#projectId)")
 *   @PreAuthorize("@projectSecurity.canAccessCandidate(#candidateId)")
 *   @PreAuthorize("@projectSecurity.canModifyCandidate(#candidateId)")
 */
@Component("projectSecurity")
@RequiredArgsConstructor
public class ProjectSecurityEvaluator {

    private final ProjectRepository projectRepository;
    private final CompanyCandidateRepository candidateRepository;

    // ─────────────────────────────────────────────
    // Project-level guards
    // ─────────────────────────────────────────────

    /**
     * Returns true if the current user is a member of the given project,
     * OR if they have BUSINESS_OWNER role (system-wide read access).
     */
    public boolean isMemberOrOwner(Long projectId) {
        UserDetailsImpl user = currentUser();
        if (user == null) return false;
        if (isOwner(user)) return true;
        return projectRepository.existsByIdAndMembersAccountId(projectId, user.getId())
                || projectRepository.existsByIdAndCreatedByAccountId(projectId, user.getId());
    }

    /**
     * Returns true if the current user is a member of the given project.
     * BUSINESS_OWNER does NOT automatically pass this check (write operations are membership-scoped).
     */
    public boolean isMember(Long projectId) {
        UserDetailsImpl user = currentUser();
        if (user == null) return false;
        return projectRepository.existsByIdAndMembersAccountId(projectId, user.getId())
                || projectRepository.existsByIdAndCreatedByAccountId(projectId, user.getId());
    }

    /**
     * BUSINESS_OWNER can read anything; BDM and RS must be project members.
     */
    public boolean isProjectReadable(Long projectId) {
        UserDetailsImpl user = currentUser();
        if (user == null) return false;
        if (isOwner(user)) return true;
        return projectRepository.existsByIdAndMembersAccountId(projectId, user.getId())
                || projectRepository.existsByIdAndCreatedByAccountId(projectId, user.getId());
    }

    /**
     * True if project member AND Staff
     */
    public boolean isStaff(Long projectId) {
        UserDetailsImpl user = currentUser();
        if (user == null || !hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF)) return false;
        return projectRepository.existsByIdAndMembersAccountId(projectId, user.getId())
                || projectRepository.existsByIdAndCreatedByAccountId(projectId, user.getId());
    }

    /**
     * True if project member AND Manager
     */
    public boolean isManager(Long projectId) {
        UserDetailsImpl user = currentUser();
        if (user == null || !hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) return false;
        return projectRepository.existsByIdAndMembersAccountId(projectId, user.getId())
                || projectRepository.existsByIdAndCreatedByAccountId(projectId, user.getId());
    }

    /**
     * Manager must be a project member. BUSINESS_OWNER has system-wide final evaluation authority.
     */
    public boolean isManagerOrOwner(Long projectId) {
        UserDetailsImpl user = currentUser();
        if (user == null) return false;
        if (isOwner(user)) return true;
        if (!hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) return false;
        return projectRepository.existsByIdAndMembersAccountId(projectId, user.getId())
                || projectRepository.existsByIdAndCreatedByAccountId(projectId, user.getId());
    }

    /**
     * True if project member AND (Staff or Manager)
     */
    public boolean isStaffOrManager(Long projectId) {
        UserDetailsImpl user = currentUser();
        if (user == null) return false;
        if (!hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF) && !hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) return false;
        return projectRepository.existsByIdAndMembersAccountId(projectId, user.getId())
                || projectRepository.existsByIdAndCreatedByAccountId(projectId, user.getId());
    }

    // ─────────────────────────────────────────────
    // Candidate-level guards
    // ─────────────────────────────────────────────

    /**
     * Returns true if the current user may READ the given candidate.
     *
     * Rules:
     *   - BUSINESS_OWNER: always allowed
     *   - BDM / BUSINESS_DEVELOPMENT_STAFF: must be a member of the candidate's project
     */
    public boolean canAccessCandidate(String candidateId) {
        UserDetailsImpl user = currentUser();
        if (user == null) return false;
        if (isOwner(user)) return true;

        Long projectId = resolveProjectId(candidateId);
        if (projectId == null) return false;

        return projectRepository.existsByIdAndMembersAccountId(projectId, user.getId())
                || projectRepository.existsByIdAndCreatedByAccountId(projectId, user.getId());
    }

    /**
     * Returns true if the current user may WRITE/MODIFY the given candidate.
     *
     * Rules:
     *   - BUSINESS_OWNER: denied (read-only role for candidates)
     *   - BDM / BUSINESS_DEVELOPMENT_STAFF: must be a member of the candidate's project
     */
    public boolean canModifyCandidate(String candidateId) {
        UserDetailsImpl user = currentUser();
        if (user == null) return false;

        Long projectId = resolveProjectId(candidateId);
        if (projectId == null) return false;

        return projectRepository.existsByIdAndMembersAccountId(projectId, user.getId())
                || projectRepository.existsByIdAndCreatedByAccountId(projectId, user.getId());
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private Long resolveProjectId(String candidateId) {
        if (!StringUtils.hasText(candidateId)) return null;
        return candidateRepository.findById(candidateId)
                .map(CompanyCandidate::getProjectId)
                .filter(StringUtils::hasText)
                .map(pid -> {
                    try { return Long.parseLong(pid); } catch (NumberFormatException e) { return null; }
                })
                .orElse(null);
    }

    private boolean isOwner(UserDetailsImpl user) {
        return hasRole(user, SystemRole.BUSINESS_OWNER);
    }

    private UserDetailsImpl currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl)) return null;
        return (UserDetailsImpl) auth.getPrincipal();
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        String roleName = "ROLE_" + role.name();
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(roleName));
    }
}
