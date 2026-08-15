package com.apms.domain.score.service;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.repository.mongo.RoleEvaluationVersionRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RoleEvaluationAuthorityService {

    public static final String MANAGER_LOCK_MESSAGE =
            "This company evaluation has been finalized by the Business Owner and can no longer be modified by Manager.";

    private final RoleEvaluationVersionRepository versionRepository;
    private final ScoreSnapshotRepository scoreSnapshotRepository;

    public SystemRole currentEvaluatorRole() {
        UserDetailsImpl user = currentUser();
        if (hasRole(user, SystemRole.BUSINESS_OWNER)) {
            return SystemRole.BUSINESS_OWNER;
        }
        if (hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            return SystemRole.BUSINESS_DEVELOPMENT_MANAGER;
        }
        if (hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF) || hasRole(user, SystemRole.RESEARCH_STAFF)) {
            return SystemRole.BUSINESS_DEVELOPMENT_STAFF;
        }
        if (hasRole(user, SystemRole.SYSTEM_ADMIN)) {
            return SystemRole.SYSTEM_ADMIN;
        }
        throw new AccessDeniedException("ROLE_EVALUATION_ACCESS_DENIED");
    }

    public SystemRole currentEvaluatorRoleOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl user)) {
            return null;
        }
        if (hasRole(user, SystemRole.BUSINESS_OWNER)) {
            return SystemRole.BUSINESS_OWNER;
        }
        if (hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            return SystemRole.BUSINESS_DEVELOPMENT_MANAGER;
        }
        if (hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF) || hasRole(user, SystemRole.RESEARCH_STAFF)) {
            return SystemRole.BUSINESS_DEVELOPMENT_STAFF;
        }
        if (hasRole(user, SystemRole.SYSTEM_ADMIN)) {
            return SystemRole.SYSTEM_ADMIN;
        }
        return null;
    }

    public boolean isOwner() {
        return currentEvaluatorRoleOrNull() == SystemRole.BUSINESS_OWNER;
    }

    public boolean isManager() {
        return currentEvaluatorRoleOrNull() == SystemRole.BUSINESS_DEVELOPMENT_MANAGER;
    }

    public boolean isManagerOrOwner() {
        SystemRole role = currentEvaluatorRoleOrNull();
        return role == SystemRole.BUSINESS_DEVELOPMENT_MANAGER || role == SystemRole.BUSINESS_OWNER;
    }

    public void assertDraftMutableByCurrentUser(RoleEvaluationDraft draft) {
        if (draft == null || isOwner()) {
            return;
        }
        if (ownerFinalEvaluationExists(draft.getTargetProfileDocumentId(), draft.getEvaluatedRole())) {
            throw new BusinessValidationException(MANAGER_LOCK_MESSAGE);
        }
    }

    public void assertManagerMayReview(RoleEvaluationDraft draft) {
        if (isOwner()) {
            return;
        }
        if (!isManager()) {
            throw new AccessDeniedException("ROLE_EVALUATION_REVIEW_FORBIDDEN");
        }
        assertDraftMutableByCurrentUser(draft);
    }

    public boolean ownerFinalEvaluationExists(String targetCompanyProfileId, CompanyRole evaluatedRole) {
        if (targetCompanyProfileId == null || evaluatedRole == null) {
            return false;
        }
        boolean versionExists = versionRepository
                .findFirstByTargetCompanyProfileIdAndEvaluatedRoleAndEvaluatorRoleAndAuthoritativeTrueOrderByApprovedAtDescCreatedAtDesc(
                        targetCompanyProfileId,
                        evaluatedRole,
                        SystemRole.BUSINESS_OWNER)
                .isPresent();
        if (versionExists) {
            return true;
        }
        return scoreSnapshotRepository
                .findFirstByTargetCompanyProfileIdAndEvaluatedRoleAndEvaluatorRoleAndAuthoritativeTrueOrderByCalculatedAtDescCreatedAtDesc(
                        targetCompanyProfileId,
                        evaluatedRole,
                        SystemRole.BUSINESS_OWNER)
                .isPresent();
    }

    public Optional<RoleEvaluationVersion> getEffectiveEvaluation(String targetCompanyProfileId, CompanyRole evaluatedRole) {
        Optional<RoleEvaluationVersion> ownerFinal = versionRepository
                .findFirstByTargetCompanyProfileIdAndEvaluatedRoleAndEvaluatorRoleAndAuthoritativeTrueOrderByApprovedAtDescCreatedAtDesc(
                        targetCompanyProfileId,
                        evaluatedRole,
                        SystemRole.BUSINESS_OWNER);
        if (ownerFinal.isPresent()) {
            return ownerFinal;
        }
        Optional<RoleEvaluationVersion> managerEvaluation = versionRepository
                .findFirstByTargetCompanyProfileIdAndEvaluatedRoleAndEvaluatorRoleOrderByApprovedAtDescCreatedAtDesc(
                        targetCompanyProfileId,
                        evaluatedRole,
                        SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        return managerEvaluation.or(() -> versionRepository
                .findFirstByTargetCompanyProfileIdAndEvaluatedRoleOrderByApprovedAtDescCreatedAtDesc(
                        targetCompanyProfileId,
                        evaluatedRole));
    }

    private UserDetailsImpl currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl)) {
            throw new AccessDeniedException("UNAUTHENTICATED");
        }
        return (UserDetailsImpl) auth.getPrincipal();
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        String roleName = "ROLE_" + role.name();
        return user.getAuthorities().stream().anyMatch(authority -> roleName.equals(authority.getAuthority()));
    }
}
