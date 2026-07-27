package com.apms.domain.profile.service;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.dto.CompanyProfileVersionResponse;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CompanyProfileVersionService {

    private final CompanyProfileVersionRepository versionRepository;

    public Page<CompanyProfileVersionResponse> getVersions(String companyProfileId, Pageable pageable) {
        checkAccess();
        return versionRepository.findByCompanyProfileIdOrderByVersionDesc(companyProfileId, pageable)
                .map(this::toResponse);
    }

    public CompanyProfileVersionResponse getVersion(String companyProfileId, Integer version) {
        checkAccess();
        CompanyProfileVersion profileVersion = versionRepository.findByCompanyProfileIdAndVersion(companyProfileId, version)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
        return toResponse(profileVersion);
    }

    private CompanyProfileVersionResponse toResponse(CompanyProfileVersion version) {
        return CompanyProfileVersionResponse.builder()
                .id(version.getId())
                .companyProfileId(version.getCompanyProfileId())
                .companyId(version.getCompanyId())
                .version(version.getVersion())
                .snapshot(version.getSnapshot())
                .createdFromProposalId(version.getCreatedFromProposalId())
                .createdFromProjectId(version.getCreatedFromProjectId())
                .createdFromTaskId(version.getCreatedFromTaskId())
                .sourceDocumentIds(version.getSourceDocumentIds())
                .changeSummary(version.getChangeSummary())
                .createdBy(version.getCreatedBy())
                .createdAt(version.getCreatedAt())
                .build();
    }

    private void checkAccess() {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        // Allowed roles according to design: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
        boolean isAllowed = hasRole(currentUser, SystemRole.SYSTEM_ADMIN) ||
                            hasRole(currentUser, SystemRole.BUSINESS_OWNER) ||
                            hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER) ||
                            hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF);

        if (!isAllowed) {
            throw new AccessDeniedException("Access denied to view profile versions");
        }
    }

    private UserDetailsImpl getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
            return (UserDetailsImpl) auth.getPrincipal();
        }
        return null;
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        String roleName = "ROLE_" + role.name();
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(roleName));
    }
}
