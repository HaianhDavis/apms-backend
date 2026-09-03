package com.apms.domain.profile.service;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.dto.CompanyProfileVersionResponse;
import com.apms.domain.profile.enums.CompanyProfileChangeSource;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import com.apms.security.UserDetailsImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyProfileVersionService {

    private final CompanyProfileVersionRepository versionRepository;
    private final CompanyProfileRepository profileRepository;
    private final UserProfileRepository userProfileRepository;
    private final ProjectRepository projectRepository;
    private final com.apms.domain.user.repository.sql.AccountRepository accountRepository;
    private final ObjectMapper objectMapper;

    public CompanyProfile resolveProfile(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new ResourceNotFoundException("Profile identifier is required");
        }
        if (profileRepository == null) {
            throw new ResourceNotFoundException("Profile repository not available");
        }
        return profileRepository.findById(identifier)
                .or(() -> profileRepository.findByCompanyId(identifier))
                .orElseThrow(() -> new ResourceNotFoundException("Company profile not found: " + identifier));
    }

    public Page<CompanyProfileVersionResponse> getVersions(String companyProfileIdOrCompanyId, Pageable pageable) {
        checkAccess();

        CompanyProfile profile = resolveProfile(companyProfileIdOrCompanyId);
        String canonicalProfileId = profile.getId();
        String companyId = profile.getCompanyId();

        Page<CompanyProfileVersion> pageResult;
        if (companyId != null && !companyId.isBlank()) {
            pageResult = versionRepository.findByCompanyProfileIdOrCompanyIdOrderByCreatedAtDesc(canonicalProfileId, companyId, pageable);
        } else {
            pageResult = versionRepository.findByCompanyProfileIdOrderByCreatedAtDesc(canonicalProfileId, pageable);
        }

        return pageResult.map(this::toResponse);
    }

    public CompanyProfileVersionResponse getVersion(String companyProfileIdOrCompanyId, String version) {
        checkAccess();

        CompanyProfile profile = resolveProfile(companyProfileIdOrCompanyId);
        String canonicalProfileId = profile.getId();
        String companyId = profile.getCompanyId();

        CompanyProfileVersion profileVersion = versionRepository
                .findByCompanyProfileIdAndVersionOrVersionLabel(canonicalProfileId, version)
                .or(() -> {
                    if (companyId != null && !companyId.isBlank()) {
                        return versionRepository.findByCompanyProfileIdAndVersionOrVersionLabel(companyId, version);
                    }
                    return Optional.empty();
                })
                .or(() -> versionRepository.findByCompanyProfileIdAndVersion(canonicalProfileId, version))
                .orElseThrow(() -> new ResourceNotFoundException("Version not found: " + version));

        return toResponse(profileVersion);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createSnapshotMap(CompanyProfile profile) {
        if (profile == null) return null;
        try {
            return objectMapper.convertValue(profile, Map.class);
        } catch (Exception e) {
            log.warn("Failed to serialize CompanyProfile snapshot for profile {}: {}", profile.getId(), e.getMessage());
            return null;
        }
    }

    public CompanyProfileVersion createAndSaveVersion(
            CompanyProfile profile,
            CompanyProfileChangeSource changeSource,
            List<String> changedFieldPaths,
            Map<String, Object> beforeValues,
            Map<String, Object> afterValues,
            String changeNote,
            String changeSummary,
            String createdFromProposalId,
            Long createdFromProjectId,
            Long createdFromTaskId,
            List<String> sourceDocumentIds,
            Long createdBy
    ) {
        int major = CompanyProfileVersionHelper.resolveMajorVersion(profile);
        int rev = CompanyProfileVersionHelper.resolveRevision(profile);
        String versionLabel = CompanyProfileVersionHelper.formatVersionLabel(major, rev);
        String legacyVersion = CompanyProfileVersionHelper.formatLegacyVersion(major, rev);

        Map<String, Object> snapshotMap = createSnapshotMap(profile);

        CompanyProfileVersion version = CompanyProfileVersion.builder()
                .companyProfileId(profile.getId())
                .companyId(profile.getCompanyId())
                .majorVersion(major)
                .revision(rev)
                .version(legacyVersion)
                .versionLabel(versionLabel)
                .changeSource(changeSource)
                .snapshot(snapshotMap)
                .changedFieldPaths(changedFieldPaths)
                .beforeValues(beforeValues)
                .afterValues(afterValues)
                .changeNote(changeNote)
                .changeSummary(changeSummary != null ? changeSummary : versionLabel + " (" + changeSource + ")")
                .createdFromProposalId(createdFromProposalId)
                .createdFromProjectId(createdFromProjectId)
                .createdFromTaskId(createdFromTaskId)
                .sourceDocumentIds(sourceDocumentIds)
                .createdBy(createdBy)
                .createdAt(LocalDateTime.now())
                .build();

        return versionRepository.save(version);
    }

    public CompanyProfileVersionResponse toResponse(CompanyProfileVersion version) {
        int major = version.getMajorVersion() != null
                ? version.getMajorVersion()
                : CompanyProfileVersionHelper.parseLegacyVersion(version.getVersion())[0];
        int rev = version.getRevision() != null
                ? version.getRevision()
                : CompanyProfileVersionHelper.parseLegacyVersion(version.getVersion())[1];

        String createdByName = null;
        if (version.getCreatedBy() != null) {
            if (userProfileRepository != null) {
                try {
                    createdByName = userProfileRepository.findByAccountId(version.getCreatedBy())
                            .map(p -> (p.getFirstName() != null ? p.getFirstName() + " " : "") + (p.getLastName() != null ? p.getLastName() : ""))
                            .filter(name -> !name.trim().isEmpty())
                            .orElse(null);
                } catch (Exception ignored) {}
            }
            if ((createdByName == null || createdByName.isBlank()) && accountRepository != null) {
                try {
                    createdByName = accountRepository.findById(version.getCreatedBy())
                            .map(com.apms.domain.user.Account::getEmail)
                            .orElse(null);
                } catch (Exception ignored) {}
            }
        }

        String projectName = null;
        if (version.getCreatedFromProjectId() != null && projectRepository != null) {
            try {
                projectName = projectRepository.findById(version.getCreatedFromProjectId())
                        .map(p -> p.getProjectName())
                        .orElse(null);
            } catch (Exception ignored) {}
        }

        return CompanyProfileVersionResponse.builder()
                .id(version.getId())
                .companyProfileId(version.getCompanyProfileId())
                .companyId(version.getCompanyId())
                .majorVersion(major)
                .revision(rev)
                .version(version.getVersion() != null ? version.getVersion() : CompanyProfileVersionHelper.formatLegacyVersion(major, rev))
                .versionLabel(version.getVersionLabel() != null ? version.getVersionLabel() : CompanyProfileVersionHelper.formatVersionLabel(major, rev))
                .changeSource(version.getChangeSource())
                .changedFieldPaths(version.getChangedFieldPaths())
                .beforeValues(version.getBeforeValues())
                .afterValues(version.getAfterValues())
                .changeNote(version.getChangeNote())
                .snapshot(version.getSnapshot())
                .createdFromProposalId(version.getCreatedFromProposalId())
                .createdFromProjectId(version.getCreatedFromProjectId())
                .projectName(projectName)
                .createdFromTaskId(version.getCreatedFromTaskId())
                .sourceDocumentIds(version.getSourceDocumentIds())
                .changeSummary(version.getChangeSummary())
                .createdBy(version.getCreatedBy())
                .createdByName(createdByName)
                .createdAt(version.getCreatedAt())
                .build();
    }

    private void checkAccess() {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

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
