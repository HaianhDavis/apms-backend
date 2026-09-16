package com.apms.domain.profile.assessment.service;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Authoritative, centralized access and eligibility evaluator for Relationship Closeness.
 * Used identically by:
 * - ProfileService (to compute ProfileResponse.canAccessRelationshipCloseness for UI visibility)
 * - CompanyRelationshipAssessmentService (to enforce security on all assessment operations)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelationshipClosenessAccessEvaluator {

    public static final Set<String> ELIGIBLE_RELATIONSHIP_TYPES = Set.of(
            "PARTNER",
            "PARTNER_WITH",
            "CUSTOMER",
            "CUSTOMER_OF",
            "SUPPLIER",
            "SUPPLIER_OF"
    );

    public static final Set<String> INELIGIBLE_RELATIONSHIP_TYPES = Set.of(
            "COMPETITOR",
            "COMPETITOR_OF",
            "POTENTIAL_PARTNER",
            "POTENTIAL_PARTNER_OF"
    );

    private static final List<ProjectStatus> VIEW_PROJECT_STATUSES = List.of(
            ProjectStatus.DRAFT,
            ProjectStatus.ACTIVE,
            ProjectStatus.COMPLETED
    );

    private static final List<ProjectStatus> WRITE_PROJECT_STATUSES = List.of(
            ProjectStatus.DRAFT,
            ProjectStatus.ACTIVE
    );

    private final CompanyProfileRepository companyProfileRepository;
    private final ProjectRepository projectRepository;
    private final OwnerOrganizationService ownerOrganizationService;
    private final Neo4jClient neo4jClient;

    /**
     * Checks if a relationship type string is eligible for Relationship Closeness.
     */
    public boolean isEligibleRelationshipType(String relType) {
        if (!StringUtils.hasText(relType)) return false;
        String normalized = relType.trim().toUpperCase();
        return ELIGIBLE_RELATIONSHIP_TYPES.contains(normalized);
    }

    /**
     * Authoritative check for UI capability:
     * "Can THIS authenticated user access Relationship Closeness for THIS company?"
     */
    public boolean canAccess(CompanyProfile profile, UserDetailsImpl currentUser) {
        if (profile == null || currentUser == null) return false;
        if (Boolean.TRUE.equals(profile.getIsHidden()) || Boolean.TRUE.equals(profile.getIsDeleted())) return false;

        String targetId = StringUtils.hasText(profile.getCompanyId()) ? profile.getCompanyId() : profile.getId();
        if (ownerOrganizationService.isOwnerCompany(targetId) || ownerOrganizationService.isOwnerCompany(profile.getId())) {
            return false;
        }

        String relType = resolveRelationshipType(targetId);
        if (!isEligibleRelationshipType(relType)) {
            return false;
        }

        if (hasRole(currentUser, SystemRole.SYSTEM_ADMIN)) {
            return false;
        }

        if (hasRole(currentUser, SystemRole.BUSINESS_OWNER)) {
            return true;
        }

        if (hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            boolean isResponsible = profile.getResponsibleManagerId() != null
                    && profile.getResponsibleManagerId().equals(currentUser.getId());
            boolean inProject = isInProjectScope(profile, currentUser.getId(), VIEW_PROJECT_STATUSES);
            return isResponsible || inProject;
        }

        if (hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF)) {
            return isInProjectScope(profile, currentUser.getId(), VIEW_PROJECT_STATUSES);
        }

        return false;
    }

    /**
     * Authoritative security enforcement for CompanyRelationshipAssessmentService.
     * Throws BusinessValidationException or AccessDeniedException if validation fails.
     */
    public void validateAssessmentAccess(String targetCompanyProfileId, UserDetailsImpl currentUser, boolean isWrite) {
        if (ownerOrganizationService.isOwnerCompany(targetCompanyProfileId)) {
            throw new BusinessValidationException("Cannot evaluate relationship closeness for the Owner Organization itself.");
        }

        CompanyProfile target = companyProfileRepository.findById(targetCompanyProfileId)
                .or(() -> companyProfileRepository.findByCompanyId(targetCompanyProfileId))
                .orElseThrow(() -> new ResourceNotFoundException("Target CompanyProfile not found: " + targetCompanyProfileId));

        if (Boolean.TRUE.equals(target.getIsHidden()) || Boolean.TRUE.equals(target.getIsDeleted())) {
            throw new BusinessValidationException("Target CompanyProfile is hidden or deleted.");
        }

        String companyId = StringUtils.hasText(target.getCompanyId()) ? target.getCompanyId() : target.getId();
        String relType = resolveRelationshipType(companyId);
        if (!isEligibleRelationshipType(relType)) {
            throw new BusinessValidationException("Relationship Closeness assessment is not available for this company relationship type.");
        }

        if (hasRole(currentUser, SystemRole.SYSTEM_ADMIN)) {
            throw new AccessDeniedException("System Admin cannot access internal relationship assessments.");
        }

        if (hasRole(currentUser, SystemRole.BUSINESS_OWNER)) {
            return;
        }

        List<ProjectStatus> statuses = isWrite ? WRITE_PROJECT_STATUSES : VIEW_PROJECT_STATUSES;

        if (hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            boolean isResponsible = target.getResponsibleManagerId() != null
                    && target.getResponsibleManagerId().equals(currentUser.getId());
            boolean inProject = isInProjectScope(target, currentUser.getId(), statuses);
            if (!isResponsible && !inProject) {
                throw new AccessDeniedException(isWrite
                        ? "Target company is not within your active project scope."
                        : "Target company is not within your project scope.");
            }
            return;
        }

        if (hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF)) {
            if (!isInProjectScope(target, currentUser.getId(), statuses)) {
                throw new AccessDeniedException(isWrite
                        ? "Target company is not within your active project scope."
                        : "Target company is not within your project scope.");
            }
            return;
        }

        throw new AccessDeniedException("You do not have permission to view relationship assessments.");
    }

    /**
     * Checks if the target company is in scope for the user in projects with the allowed statuses.
     * Project.targetCompanyProfileId stores the canonical companyId (or Mongo _id).
     */
    public boolean isInProjectScope(CompanyProfile profile, Long accountId, List<ProjectStatus> allowedStatuses) {
        if (profile == null || accountId == null) return false;

        if (StringUtils.hasText(profile.getId()) &&
                projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(profile.getId(), accountId, allowedStatuses)) {
            return true;
        }

        if (StringUtils.hasText(profile.getCompanyId()) && !profile.getCompanyId().equals(profile.getId()) &&
                projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(profile.getCompanyId(), accountId, allowedStatuses)) {
            return true;
        }

        return false;
    }

    /**
     * Resolves the authoritative relationship type from Neo4j (owner -> target), falling back to linked projects.
     */
    public String resolveRelationshipType(String companyId) {
        if (!StringUtils.hasText(companyId)) return null;
        try {
            String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
            if (companyId.equals(ownerCompanyId)) return null;

            String cypher = "MATCH (:Company {companyId: $ownerCompanyId})-[r]->(:Company {companyId: $companyId}) RETURN type(r) LIMIT 1";
            java.util.List<String> types = new java.util.ArrayList<>(neo4jClient.query(cypher)
                    .bind(ownerCompanyId).to("ownerCompanyId")
                    .bind(companyId).to("companyId")
                    .fetchAs(String.class)
                    .all());
            if (types != null && !types.isEmpty()) {
                return types.get(0);
            }
        } catch (Exception e) {
            log.debug("Failed to resolve Neo4j relationship for companyId {}: {}", companyId, e.getMessage());
        }

        try {
            Optional<CompanyProfile> profileOpt = companyProfileRepository.findByCompanyId(companyId)
                    .or(() -> companyProfileRepository.findById(companyId));
            if (profileOpt.isPresent() && profileOpt.get().getSourceRefs() != null && profileOpt.get().getSourceRefs().getProjectIds() != null) {
                for (String pid : profileOpt.get().getSourceRefs().getProjectIds()) {
                    try {
                        Long pId = Long.parseLong(pid);
                        Optional<Project> projOpt = projectRepository.findById(pId);
                        if (projOpt.isPresent() && projOpt.get().getTargetRelationshipType() != null) {
                            return projOpt.get().getTargetRelationshipType().name();
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        String roleName = "ROLE_" + role.name();
        return user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(roleName));
    }
}
