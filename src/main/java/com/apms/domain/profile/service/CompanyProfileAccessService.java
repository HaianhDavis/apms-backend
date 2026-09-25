package com.apms.domain.profile.service;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.monitoring.repository.CompanyMonitoringAssignmentRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyProfileAccessService {

    private static final List<ProjectStatus> ALL_PROJECT_STATUSES = Arrays.asList(ProjectStatus.values());

    private final CompanyProfileRepository companyProfileRepository;
    private final ProjectRepository projectRepository;
    private final CompanyMonitoringAssignmentRepository monitoringAssignmentRepository;
    private final OwnerOrganizationService ownerOrganizationService;

    /**
     * Ensures that the requested company profile exists, is official/approved,
     * is not deleted or hidden.
     * - BUSINESS_OWNER or SYSTEM_ADMIN: can access ALL companies.
     * - BUSINESS_DEVELOPMENT_MANAGER: can access ONLY companies they manage.
     * - Other roles: ACCESS_FORBIDDEN.
     *
     * @param companyProfileId the ID of the company profile
     * @param user             the current authenticated user
     * @return the CompanyProfile if accessible
     */
    public CompanyProfile requireOwnerAccessibleOfficialCompanyProfile(String companyProfileId, UserDetailsImpl user) {
        CompanyProfile profile = companyProfileRepository.findById(companyProfileId)
                .or(() -> companyProfileRepository.findByCompanyId(companyProfileId))
                .orElseThrow(() -> new ResourceNotFoundException("COMPANY_PROFILE_NOT_FOUND"));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("COMPANY_PROFILE_NOT_FOUND");
        }

        if (Boolean.TRUE.equals(profile.getIsHidden())) {
            throw new AccessDeniedException("COMPANY_PROFILE_ACCESS_DENIED");
        }

        if (!"APPROVED".equals(profile.getReviewStatus())) {
            throw new BusinessValidationException("COMPANY_PROFILE_NOT_OFFICIAL");
        }

        // 1. BUSINESS_OWNER or SYSTEM_ADMIN: global access to all companies
        if (hasRole(user, SystemRole.BUSINESS_OWNER) || hasRole(user, SystemRole.SYSTEM_ADMIN)) {
            return profile;
        }

        // 2. BUSINESS_DEVELOPMENT_MANAGER: access only if managing this company
        if (hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            if (isManagerAuthorizedForCompany(profile, user.getId())) {
                return profile;
            }
            log.warn("Manager ID {} denied secure access to company profile {}: not a managing manager", user.getId(), companyProfileId);
            throw new AccessDeniedException("MANAGER_NOT_AUTHORIZED_FOR_COMPANY");
        }

        // 3. Other roles
        throw new AccessDeniedException("ACCESS_FORBIDDEN");
    }

    /**
     * Checks if a manager is authorized to manage the given company profile.
     */
    public boolean isManagerAuthorizedForCompany(CompanyProfile profile, Long managerId) {
        if (profile == null || managerId == null) {
            return false;
        }

        // 1. Direct responsible manager on profile (quản lý phụ trách trực tiếp)
        if (managerId.equals(profile.getResponsibleManagerId())) {
            return true;
        }

        // 2. Doanh nghiệp chủ quản - My Enterprise / FPT (global owner company)
        if (ownerOrganizationService.isOwnerCompany(profile.getId()) || ownerOrganizationService.isOwnerCompany(profile.getCompanyId())) {
            return true;
        }

        return false;
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role.name()));
    }
}
