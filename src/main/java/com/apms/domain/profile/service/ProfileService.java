package com.apms.domain.profile.service;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.common.event.CandidateApprovedEvent;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.notification.service.NotificationService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.dto.FinancialReportRequest;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.ProfileSourcesResponse;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.dto.UpdateCompanyProfileRequest;
import com.apms.domain.profile.dto.UpdateOwnerCompanyProfileRequest;
import com.apms.domain.profile.dto.AdminUpdateEnterpriseBasicInfoRequest;
import com.apms.domain.profile.dto.AdminUpdateEnterpriseBusinessFieldsRequest;
import com.apms.domain.profile.dto.AdminUpdateEnterpriseLeadershipRequest;
import com.apms.domain.profile.dto.AdminEnterpriseLeadershipMemberRequest;
import com.apms.domain.profile.dto.AdminEnterpriseProductRequest;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.common.enums.AuditAction;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.security.UserDetailsImpl;
import com.apms.domain.crawler.repository.TrackedCompanyRepository;
import com.apms.domain.crawler.service.TrackedCompanyCache;
import com.apms.domain.crawler.domain.TrackedCompany;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Comparator;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final CompanyProfileRepository profileRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final ProjectRepository projectRepository;
    private final MongoTemplate mongoTemplate;
    private final Neo4jClient neo4jClient;
    private final AuditLogService auditLogService;
    private final OwnerOrganizationService ownerOrganizationService;
    private final TrackedCompanyRepository trackedCompanyRepository;
    private final TrackedCompanyCache trackedCompanyCache;
    private final com.apms.domain.project.service.ProjectTargetProfileResolver projectTargetProfileResolver;
    private final com.apms.domain.user.repository.sql.AccountRepository accountRepository;
    private final CompanyProfileVersionService versionService;
    private final com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository profileVersionRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.apms.domain.profile.assessment.service.RelationshipClosenessAccessEvaluator relationshipClosenessAccessEvaluator;
    private final NotificationService notificationService;
    private final com.apms.domain.reference.service.IndustryCatalogService industryCatalogService;
    private final CompanyProfileOfficialEvaluator companyProfileOfficialEvaluator;
    private final com.apms.domain.profile.repository.mongo.CompanyProfileManagerHistoryRepository historyRepository;
    private final com.apms.domain.user.repository.sql.UserProfileRepository userProfileRepository;

    // ─────────────────────────────────────────────
    // EVENT LISTENER
    // ─────────────────────────────────────────────

    /**
     * Listens to CandidateApprovedEvent to create or update the official CompanyProfile.
     * Note: In a fully distributed environment, this could be an async listener or message queue consumer.
     */
    @EventListener
    @Order(1)
    @Transactional // Ensures it participates in the overarching transaction if one exists
    public void handleCandidateApprovedEvent(CandidateApprovedEvent event) {
        log.info("Received CandidateApprovedEvent for candidateId: {}", event.getCandidateId());

        CompanyCandidate candidate = candidateRepository.findById(event.getCandidateId())
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found: " + event.getCandidateId()));

        Project project = projectRepository.findById(Long.valueOf(event.getProjectId()))
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + event.getProjectId()));

        CompanyProfile approvedProfile = project.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY
                ? updateExistingProfile(project, candidate)
                : createNewProfile(project, candidate);
        projectTargetProfileResolver.backfillPartnerContractTasks(project, approvedProfile.getCompanyId());

        if (candidate.getBusiness() != null && candidate.getBusiness().getIndustries() != null) {
            industryCatalogService.upsertApprovedIndustries(
                    candidate.getBusiness().getIndustries(),
                    candidate.getId(),
                    approvedProfile.getCompanyId(),
                    candidate.getReview() != null && candidate.getReview().getReviewedBy() != null
                            ? Long.valueOf(candidate.getReview().getReviewedBy()) : null
            );
        }

        // Add to TrackedCompany if not exists
        if (candidate.getIdentity() != null) {
            String legalName = candidate.getIdentity().getLegalName();
            String tradeName = candidate.getIdentity().getTradeName();

            String primaryName = null;
            if (StringUtils.hasText(legalName)) {
                primaryName = legalName.trim();
            } else if (StringUtils.hasText(tradeName)) {
                primaryName = tradeName.trim();
            }

            if (StringUtils.hasText(primaryName)) {
                if (!trackedCompanyRepository.existsByCompanyNameIgnoreCase(primaryName)) {
                    java.util.List<String> aliases = new java.util.ArrayList<>();
                    if (StringUtils.hasText(tradeName) && !tradeName.trim().equalsIgnoreCase(primaryName)) {
                        aliases.add(tradeName.trim());
                    }

                    TrackedCompany newTracked = TrackedCompany.builder()
                            .companyName(primaryName)
                            .aliases(aliases)
                            .isActive(true)
                            .build();
                    trackedCompanyRepository.save(newTracked);
                    trackedCompanyCache.forceRefresh();
                    log.info("Added new TrackedCompany from Candidate: {} with aliases {}", primaryName, aliases);
                }
            }
        }

        log.info("Candidate {} source documents remain as research/evidence only; they are not published to Company Profile documents.", candidate.getId());
    }

    private CompanyProfile createNewProfile(Project project, CompanyCandidate candidate) {
        java.util.Optional<CompanyProfile> existingProfile = profileRepository.findByCandidateId(candidate.getId());
        if (existingProfile.isPresent()) {
            CompanyProfile profile = existingProfile.get();
            log.info("Reusing existing CompanyProfile {} for candidate {}", profile.getCompanyId(), candidate.getId());
            addSourceRefs(profile, project, candidate);
            profile = profileRepository.save(profile);
            linkProjectToProfile(project, profile);
            linkCandidateToProfile(candidate, profile);
            return profile;
        }

        // Check if Project already owns a linked profile shell
        CompanyProfile profile = null;
        if (StringUtils.hasText(project.getTargetCompanyProfileId())) {
            profile = profileRepository.findByCompanyId(project.getTargetCompanyProfileId())
                    .or(() -> profileRepository.findById(project.getTargetCompanyProfileId()))
                    .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                    .orElse(null);
        }
        if (profile == null && StringUtils.hasText(project.getTargetCompanyTaxCode())) {
            String normTax = project.getTargetCompanyTaxCode().replaceAll("[\\s\\-]", "").trim();
            profile = profileRepository.findByIdentityTaxCode(normTax)
                    .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                    .orElse(null);
        }
        if (profile == null && project.getId() != null) {
            java.util.List<CompanyProfile> byProj = profileRepository.findByProjectId(String.valueOf(project.getId()));
            if (!byProj.isEmpty()) {
                profile = byProj.stream().filter(p -> !Boolean.TRUE.equals(p.getIsDeleted())).findFirst().orElse(null);
            }
        }

        String companyId = profile != null ? profile.getCompanyId() : UUID.randomUUID().toString();
        log.info("Applying approved candidate {} to CompanyProfile companyId: {}", candidate.getId(), companyId);

        CompanyProfile.Identity identity = mapIdentity(candidate.getIdentity());
        if (identity == null) {
            identity = (profile != null && profile.getIdentity() != null) ? profile.getIdentity() : CompanyProfile.Identity.builder().build();
        }
        
        // Authoritative project target identity wins for legalName and taxCode
        if (StringUtils.hasText(project.getTargetCompanyTaxCode())) {
            identity.setTaxCode(project.getTargetCompanyTaxCode().replaceAll("[\\s\\-]", "").trim());
            log.info("Populated authoritative taxCode from Project target identity: {}", identity.getTaxCode());
        } else if (profile != null && profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getTaxCode())) {
            identity.setTaxCode(profile.getIdentity().getTaxCode());
        }
        if (StringUtils.hasText(project.getTargetCompanyName())) {
            identity.setLegalName(project.getTargetCompanyName());
            log.info("Populated authoritative legalName from Project target identity: {}", project.getTargetCompanyName());
        } else if (profile != null && profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getLegalName())) {
            identity.setLegalName(profile.getIdentity().getLegalName());
        }

        Long canonicalManagerId = findCanonicalManager(project);

        if (profile == null) {
            profile = CompanyProfile.builder()
                    .companyId(companyId)
                    .majorVersion(1)
                    .revision(0)
                    .version(CompanyProfileVersionHelper.formatLegacyVersion(1, 0))
                    .metadata(CompanyProfile.Metadata.builder()
                            .createdBy("SYSTEM")
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build())
                    .build();
        } else {
            if (profile.getMetadata() == null) {
                profile.setMetadata(CompanyProfile.Metadata.builder().createdAt(LocalDateTime.now()).build());
            }
            profile.getMetadata().setUpdatedAt(LocalDateTime.now());
            profile.getMetadata().setLastModifiedBy("SYSTEM");
        }

        profile.setIdentity(identity);
        profile.setBusiness(mapBusiness(candidate.getBusiness()));
        profile.setCompanySize(mapCompanySize(candidate.getCompanySize()));
        profile.setContact(mapContact(candidate.getContact()));
        profile.setInsights(mapInsights(candidate.getInsights()));
        profile.setFinancial(candidate.getFinancial());
        profile.setMarket(candidate.getMarket());
        profile.setInnovation(candidate.getInnovation());
        profile.setRisk(candidate.getRisk());
        profile.setCompliance(candidate.getCompliance());
        profile.setReviewStatus("APPROVED");
        profile.setIsHidden(true);
        if (canonicalManagerId != null) {
            profile.setResponsibleManagerId(canonicalManagerId);
        }

        addSourceRefs(profile, project, candidate);

        profile = profileRepository.save(profile);
        linkProjectToProfile(project, profile);
        linkCandidateToProfile(candidate, profile);

        boolean hasExistingVersion = profileVersionRepository.existsByCompanyProfileId(profile.getId());
        if (!hasExistingVersion) {
            versionService.createAndSaveVersion(
                    profile,
                    com.apms.domain.profile.enums.CompanyProfileChangeSource.INITIAL_PROFILE_CREATION,
                    null,
                    null,
                    null,
                    "Initial approved official profile",
                    "Initial profile creation (" + profile.getVersionLabel() + ")",
                    null,
                    project.getId(),
                    null,
                    candidate.getSourceDocumentIds(),
                    canonicalManagerId
            );
        }

        log.info("Successfully updated CompanyProfile for companyId: {}, version: {}", companyId, profile.getVersionLabel());
        return profile;
    }

    private CompanyProfile updateExistingProfile(Project project, CompanyCandidate candidate) {
        String targetProfileId = project.getTargetCompanyProfileId();
        if (!StringUtils.hasText(targetProfileId)) {
            log.error("UPDATE_EXISTING_COMPANY project missing targetCompanyProfileId");
            throw new ResourceNotFoundException("UPDATE_EXISTING_COMPANY project missing targetCompanyProfileId");
        }

        CompanyProfile profile = profileRepository.findById(targetProfileId)
                .or(() -> profileRepository.findByCompanyId(targetProfileId))
                .orElseThrow(() -> new ResourceNotFoundException("Target CompanyProfile not found: " + targetProfileId));

        log.info("Updating EXISTING CompanyProfile with companyId: {}", profile.getCompanyId());

        // Enrich/Update data - simple overwrite for now
        // In a real system, you might merge based on confidence scores or manual review flags
        CompanyProfile.Identity newIdentity = candidate.getIdentity() != null ? mapIdentity(candidate.getIdentity()) : null;
        if (newIdentity == null && profile.getIdentity() == null) {
            newIdentity = CompanyProfile.Identity.builder().build();
        }
        
        if (newIdentity != null) {
            // Project target identity is authoritative; fallback to existing profile identity
            if (StringUtils.hasText(project.getTargetCompanyTaxCode())) {
                newIdentity.setTaxCode(project.getTargetCompanyTaxCode().replaceAll("[\\s\\-]", "").trim());
            } else if (StringUtils.hasText(profile.getIdentity() != null ? profile.getIdentity().getTaxCode() : null)) {
                newIdentity.setTaxCode(profile.getIdentity().getTaxCode());
            }

            if (StringUtils.hasText(project.getTargetCompanyName())) {
                newIdentity.setLegalName(project.getTargetCompanyName());
            } else if (StringUtils.hasText(profile.getIdentity() != null ? profile.getIdentity().getLegalName() : null)) {
                newIdentity.setLegalName(profile.getIdentity().getLegalName());
            }
            profile.setIdentity(newIdentity);
        }
        
        if (profile.getIdentity() == null) {
            profile.setIdentity(CompanyProfile.Identity.builder().build());
        }
        
        // Safe propagation from Project
        if (!StringUtils.hasText(profile.getIdentity().getTaxCode()) && StringUtils.hasText(project.getTargetCompanyTaxCode())) {
            profile.getIdentity().setTaxCode(project.getTargetCompanyTaxCode().replaceAll("[\\s\\-]", "").trim());
            log.info("Populated missing profile taxCode from Project target identity");
        }
        if (!StringUtils.hasText(profile.getIdentity().getLegalName()) && StringUtils.hasText(project.getTargetCompanyName())) {
            profile.getIdentity().setLegalName(project.getTargetCompanyName());
        }
        if (candidate.getBusiness() != null) profile.setBusiness(mapBusiness(candidate.getBusiness()));
        if (candidate.getCompanySize() != null) profile.setCompanySize(mapCompanySize(candidate.getCompanySize()));
        if (candidate.getContact() != null) profile.setContact(mapContact(candidate.getContact()));
        if (candidate.getInsights() != null) profile.setInsights(mapInsights(candidate.getInsights()));
        if (candidate.getFinancial() != null) profile.setFinancial(candidate.getFinancial());
        if (candidate.getMarket() != null) profile.setMarket(candidate.getMarket());
        if (candidate.getInnovation() != null) profile.setInnovation(candidate.getInnovation());
        if (candidate.getRisk() != null) profile.setRisk(candidate.getRisk());
        if (candidate.getCompliance() != null) profile.setCompliance(candidate.getCompliance());

        boolean wasApproved = "APPROVED".equals(profile.getReviewStatus());
        profile.setReviewStatus("APPROVED");
        if (!wasApproved) {
            profile.setIsHidden(true);
        }

        boolean alreadyApplied = profileVersionRepository.existsByCompanyProfileIdAndCreatedFromProjectId(profile.getId(), project.getId());
        if (!alreadyApplied) {
            profile.incrementMajorVersion();
        }

        profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        profile.getMetadata().setLastModifiedBy("SYSTEM");

        Long canonicalManagerId = profile.getResponsibleManagerId();
        if (canonicalManagerId == null) {
            canonicalManagerId = findCanonicalManager(project);
            if (canonicalManagerId != null) {
                profile.setResponsibleManagerId(canonicalManagerId);
            }
        }

        addSourceRefs(profile, project, candidate);

        profile = profileRepository.save(profile);
        linkCandidateToProfile(candidate, profile);

        if (!alreadyApplied) {
            versionService.createAndSaveVersion(
                    profile,
                    com.apms.domain.profile.enums.CompanyProfileChangeSource.PROJECT_PROFILE_UPDATE,
                    null,
                    null,
                    null,
                    "Official profile generation updated from project " + project.getProjectName(),
                    "Applied UPDATE_EXISTING_COMPANY project " + project.getId() + " (" + profile.getVersionLabel() + ")",
                    null,
                    project.getId(),
                    null,
                    candidate.getSourceDocumentIds(),
                    canonicalManagerId
            );
        }

        log.info("Successfully updated CompanyProfile for companyId: {}, new version: {}", profile.getCompanyId(), profile.getVersionLabel());
        return profile;
    }

    private void addSourceRefs(CompanyProfile profile, Project project, CompanyCandidate candidate) {
        if (profile.getSourceRefs() == null) {
            profile.setSourceRefs(new CompanyProfile.SourceRefs());
        }
        profile.getSourceRefs().getProjectIds().add(String.valueOf(project.getId()));
        profile.getSourceRefs().getCandidateIds().add(candidate.getId());

        if (StringUtils.hasText(candidate.getImportJobId())) {
            profile.getSourceRefs().getImportJobIds().add(candidate.getImportJobId());
        }
        profile.getSourceRefs().getRawDocumentIds().addAll(resolveSourceDocumentIds(candidate));
    }

    private void linkProjectToProfile(Project project, CompanyProfile profile) {
        if (!StringUtils.hasText(project.getTargetCompanyProfileId())) {
            project.setTargetCompanyProfileId(profile.getCompanyId());
            projectRepository.save(project);
            log.info("Linked project {} to CompanyProfile companyId {}", project.getId(), profile.getCompanyId());
        }
    }

    private void linkCandidateToProfile(CompanyCandidate candidate, CompanyProfile profile) {
        CompanyCandidate.Lifecycle lifecycle = candidate.getLifecycle();
        if (lifecycle == null) {
            lifecycle = new CompanyCandidate.Lifecycle();
            candidate.setLifecycle(lifecycle);
        }
        lifecycle.setStatus(candidate.getStatus());
        lifecycle.setConvertedCompanyProfileId(profile.getCompanyId());
        candidateRepository.save(candidate);
    }

    private Long findCanonicalManager(Project project) {
        if (project.getMembers() == null) return null;
        java.util.List<com.apms.domain.project.ProjectMember> activeManagers = project.getMembers().stream()
                .filter(m -> m.getProjectRole() == com.apms.common.enums.ProjectRole.LEADER 
                        && Boolean.TRUE.equals(m.getAccount().getIsActive()))
                .toList();
        
        if (activeManagers.size() == 1) {
            return activeManagers.get(0).getAccountId();
        } else if (activeManagers.isEmpty()) {
            com.apms.domain.user.Account creator = project.getCreatedByAccount();
            if (creator != null && Boolean.TRUE.equals(creator.getIsActive()) && 
                creator.getRoles().contains(com.apms.common.enums.SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
                return creator.getId();
            }
        }
        return null;
    }

    private java.util.List<String> resolveSourceDocumentIds(CompanyCandidate candidate) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (candidate.getSourceDocumentIds() != null) {
            candidate.getSourceDocumentIds().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(ids::add);
        }
        if (StringUtils.hasText(candidate.getRawDocumentId())) {
            ids.add(candidate.getRawDocumentId().trim());
        }
        return new java.util.ArrayList<>(ids);
    }

    // ─────────────────────────────────────────────
    // READ OPERATIONS
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ProfileResponse> getAllProfiles(Pageable pageable) {
        return profileRepository.findAll(newestFirst(pageable)).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfileByCompanyId(String companyId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .or(() -> profileRepository.findById(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found for companyId: " + companyId));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("CompanyProfile not found for companyId: " + companyId);
        }

        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getApprovedProfileResponse(String companyProfileId) {
        CompanyProfile profile = profileRepository.findById(companyProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found for ID: " + companyProfileId));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("CompanyProfile not found for ID: " + companyProfileId);
        }

        if (!"APPROVED".equals(profile.getReviewStatus())) {
            throw new com.apms.common.exception.BusinessValidationException("CompanyProfile must be approved.");
        }
        
        if (Boolean.TRUE.equals(profile.getIsHidden())) {
            throw new com.apms.common.exception.BusinessValidationException("CompanyProfile is not published.");
        }

        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchCompanyProfiles(String keyword, String industry, String market, String reviewStatus, String relationshipType, boolean excludeOwner, Pageable pageable) {
        return searchCompanyProfiles(keyword, industry, market, reviewStatus, relationshipType, excludeOwner, null, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchBusinessFacingProfiles(String keyword, String industry, String market, String relationshipType, boolean excludeOwner, Pageable pageable) {
        return searchCompanyProfiles(keyword, industry, market, "APPROVED", relationshipType, excludeOwner, null, com.apms.common.enums.ProfileVisibility.PUBLISHED, null, pageable);
    }

    /**
     * Get unique industries across all profiles that are not deleted.
     */
    @Transactional(readOnly = true)
    public java.util.List<String> getDistinctIndustries() {
        java.util.List<String> catalogIndustries = industryCatalogService.getDistinctActiveIndustryNames();
        if (catalogIndustries != null && !catalogIndustries.isEmpty()) {
            return catalogIndustries;
        }
        return mongoTemplate.findDistinct("business.industries", CompanyProfile.class, String.class);
    }

    /**
     * Get unique relationship types across all authoritative profiles that are not deleted or hidden.
     */
    @Transactional(readOnly = true)
    public java.util.List<String> getDistinctRelationshipTypes(boolean excludeOwner, Long managerId) {
        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
        Criteria criteria = Criteria.where("isDeleted").ne(true).and("isHidden").ne(true);
        if (managerId != null) {
            criteria.orOperator(
                    Criteria.where("metadata.createdBy").is(managerId.toString()),
                    Criteria.where("responsibleManagerId").is(managerId)
            );
        }

        List<CompanyProfile> profiles = mongoTemplate.find(Query.query(criteria), CompanyProfile.class);
        Set<String> distinctTypes = new LinkedHashSet<>();
        for (CompanyProfile p : profiles) {
            if (excludeOwner && ownerCompanyId != null && ownerCompanyId.equals(p.getCompanyId())) {
                continue;
            }
            String rel = resolveRelationshipType(p.getCompanyId());
            if (StringUtils.hasText(rel)) {
                distinctTypes.add(rel.trim());
            }
        }
        return new ArrayList<>(distinctTypes);
    }

    /**
     * Searches CompanyProfiles, optionally restricted to a set of accessible companyIds.
     *
     * @param allowedCompanyIds the only companyIds the caller may see, or {@code null} for unrestricted access.
     */
    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchCompanyProfiles(String keyword, String industry, String market, String reviewStatus, String relationshipType, boolean excludeOwner, Set<String> allowedCompanyIds, com.apms.common.enums.ProfileVisibility visibility, Long managerId, Pageable pageable) {
        return searchCompanyProfiles(keyword, industry, market, reviewStatus, relationshipType, excludeOwner, allowedCompanyIds, visibility, managerId, false, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchCompanyProfiles(String keyword, String industry, String market, String reviewStatus, String relationshipType, boolean excludeOwner, Set<String> allowedCompanyIds, com.apms.common.enums.ProfileVisibility visibility, Long managerId, Boolean officialOnly, Pageable pageable) {
        return searchCompanyProfiles(keyword, industry, market, reviewStatus, relationshipType, excludeOwner, allowedCompanyIds, visibility, managerId, null, officialOnly, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchCompanyProfiles(String keyword, String industry, String market, String reviewStatus, String relationshipType, boolean excludeOwner, Set<String> allowedCompanyIds, com.apms.common.enums.ProfileVisibility visibility, Long managerId, Long managedByManagerId, Boolean officialOnly, Pageable pageable) {
        Pageable effectivePageable = newestFirst(pageable);
        Criteria criteria = Criteria.where("isDeleted").ne(true);

        if (Boolean.TRUE.equals(officialOnly)) {
            if (!StringUtils.hasText(reviewStatus)) {
                criteria.and("reviewStatus").ne("UNVERIFIED");
            }
        }

        if (visibility != null) {
            if (visibility == com.apms.common.enums.ProfileVisibility.HIDDEN) {
                criteria.and("isHidden").is(true);
            } else {
                criteria.and("isHidden").ne(true);
            }
        }

        if (StringUtils.hasText(keyword)) {
            criteria.orOperator(
                    Criteria.where("identity.legalName").regex(keyword, "i"),
                    Criteria.where("identity.tradeName").regex(keyword, "i")
            );
        }
        if (StringUtils.hasText(industry)) {
            criteria.and("business.industries").is(industry);
        }
        if (StringUtils.hasText(market)) {
            criteria.and("business.markets").is(market);
        }
        if (StringUtils.hasText(reviewStatus)) {
            criteria.and("reviewStatus").is(reviewStatus);
        }

        if (managedByManagerId != null) {
            criteria.and("responsibleManagerId").is(managedByManagerId);
        } else if (managerId != null) {
            criteria.orOperator(
                    Criteria.where("metadata.createdBy").is(managerId.toString()),
                    Criteria.where("responsibleManagerId").is(managerId)
            );
        }

        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();

        List<String> neo4jCompanyIds = null;
        if (StringUtils.hasText(relationshipType)) {
            // 1. Validate relationshipType
            java.util.List<String> validTypes = java.util.List.of("PARTNER_WITH", "COMPETITOR_OF", "POTENTIAL_PARTNER_OF", "SUPPLIER_OF", "CUSTOMER_OF");
            if (!validTypes.contains(relationshipType)) {
                return Page.empty(pageable);
            }

            // 2. Query Neo4j for relationships with the owner company
            String cypher = String.format("""
                MATCH (:Company {companyId: $ownerCompanyId})-[:%s]-(c:Company)
                WHERE c.companyId <> $ownerCompanyId
                RETURN DISTINCT c.companyId AS companyId
                """, relationshipType);
            List<String> rawCompanyIds = new java.util.ArrayList<>(neo4jClient.query(cypher)
                    .bind(ownerCompanyId).to("ownerCompanyId")
                    .fetchAs(String.class)
                    .mappedBy((typeSystem, record) -> record.get("companyId").asString())
                    .all());

            // 3. Enforce canonical consistency: filter must match the exact canonical relationship returned for display
            neo4jCompanyIds = new java.util.ArrayList<>();
            for (String cid : rawCompanyIds) {
                String canonical = resolveRelationshipType(cid);
                if (relationshipType.equalsIgnoreCase(canonical)) {
                    neo4jCompanyIds.add(cid);
                }
            }

            if (neo4jCompanyIds.isEmpty()) {
                return Page.empty(pageable);
            }
        }


        if (allowedCompanyIds != null) {
            // Scoped caller (Staff): intersect the allowed set with any other companyId filter.
            Set<String> companyIdFilter = new LinkedHashSet<>(allowedCompanyIds);
            if (excludeOwner) {
                companyIdFilter.remove(ownerCompanyId);
            }
            if (neo4jCompanyIds != null) {
                companyIdFilter.retainAll(neo4jCompanyIds);
            }
            if (companyIdFilter.isEmpty()) {
                return Page.empty(pageable);
            }
            criteria.and("companyId").in(companyIdFilter);
        } else if (excludeOwner) {
            if (neo4jCompanyIds != null) {
                Set<String> filter = new LinkedHashSet<>(neo4jCompanyIds);
                filter.remove(ownerCompanyId);
                if (filter.isEmpty()) {
                    return Page.empty(pageable);
                }
                criteria.and("companyId").in(filter);
            } else {
                criteria.and("companyId").ne(ownerCompanyId);
            }
        } else if (neo4jCompanyIds != null) {
            criteria.and("companyId").in(neo4jCompanyIds);
        }

        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, CompanyProfile.class);
        query.with(effectivePageable);
        java.util.List<CompanyProfile> profiles = mongoTemplate.find(query, CompanyProfile.class);

        if (Boolean.TRUE.equals(officialOnly)) {
            profiles = profiles.stream()
                    .filter(companyProfileOfficialEvaluator::isOfficial)
                    .collect(java.util.stream.Collectors.toList());
        }

        return new PageImpl<>(profiles, effectivePageable, total).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchProfilesByName(String name, boolean excludeOwner, Pageable pageable) {
        return searchProfilesByName(name, excludeOwner, null, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchBusinessFacingProfilesByName(String name, boolean excludeOwner, Pageable pageable) {
        return searchProfilesByName(name, excludeOwner, null, null, com.apms.common.enums.ProfileVisibility.PUBLISHED, pageable);
    }

    /**
     * Searches CompanyProfiles by name, optionally restricted to a set of accessible companyIds.
     *
     * @param allowedCompanyIds the only companyIds the caller may see, or {@code null} for unrestricted access.
     */
    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchProfilesByName(String name, boolean excludeOwner, Set<String> allowedCompanyIds, String reviewStatus, com.apms.common.enums.ProfileVisibility visibility, Pageable pageable) {
        Pageable effectivePageable = newestFirst(pageable);
        Criteria criteria = Criteria.where("isDeleted").ne(true);

        if (StringUtils.hasText(reviewStatus)) {
            criteria.and("reviewStatus").is(reviewStatus);
        }

        if (visibility != null) {
            if (visibility == com.apms.common.enums.ProfileVisibility.HIDDEN) {
                criteria.and("isHidden").is(true);
            } else {
                criteria.and("isHidden").ne(true);
            }
        }

        if (StringUtils.hasText(name)) {
            criteria.orOperator(
                    Criteria.where("identity.legalName").regex(name, "i"),
                    Criteria.where("identity.tradeName").regex(name, "i")
            );
        }

        if (allowedCompanyIds != null) {
            Set<String> companyIdFilter = new LinkedHashSet<>(allowedCompanyIds);
            if (excludeOwner) {
                companyIdFilter.remove(ownerOrganizationService.getOwnerCompanyId());
            }
            if (companyIdFilter.isEmpty()) {
                return Page.empty(pageable);
            }
            criteria.and("companyId").in(companyIdFilter);
        } else if (excludeOwner) {
            criteria.and("companyId").ne(ownerOrganizationService.getOwnerCompanyId());
        }
        Query query = new Query(criteria).with(effectivePageable);
        java.util.List<CompanyProfile> profiles = mongoTemplate.find(query, CompanyProfile.class);
        long total = mongoTemplate.count(new Query(criteria), CompanyProfile.class);
        return new PageImpl<>(profiles, effectivePageable, total).map(this::toResponse);
    }

    private Pageable newestFirst(Pageable pageable) {
        Sort defaultSort = Sort.by(
                Sort.Order.desc("metadata.updatedAt"),
                Sort.Order.desc("metadata.createdAt"),
                Sort.Order.desc("_id")
        );
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort().and(defaultSort) : defaultSort;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    @Transactional(readOnly = true)
    public ProfileSourcesResponse getProfileSources(String companyId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found for companyId: " + companyId));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("CompanyProfile not found for companyId: " + companyId);
        }

        return ProfileSourcesResponse.builder()
                .companyId(profile.getCompanyId())
                .projectIds(profile.getSourceRefs().getProjectIds())
                .importJobIds(profile.getSourceRefs().getImportJobIds())
                .rawDocumentIds(profile.getSourceRefs().getRawDocumentIds())
                .candidateIds(profile.getSourceRefs().getCandidateIds())
                .build();
    }

    // ─────────────────────────────────────────────
    // WRITE OPERATIONS
    // ─────────────────────────────────────────────

    @Transactional
    public ProfileResponse updateProfile(String companyId, UpdateCompanyProfileRequest request) {
        UserDetailsImpl currentUser = null;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
                currentUser = (UserDetailsImpl) auth.getPrincipal();
            }
        } catch (Exception ignored) {}
        return updateProfile(companyId, request, currentUser);
    }

    @Transactional
    public ProfileResponse updateProfile(String companyId, UpdateCompanyProfileRequest request, UserDetailsImpl currentUser) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .or(() -> profileRepository.findById(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found: " + companyId));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("CompanyProfile not found: " + companyId);
        }

        if (currentUser != null) {
            boolean isAdmin = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
            boolean isManager = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_MANAGER"));
            if (!isAdmin && !isManager) {
                throw new org.springframework.security.access.AccessDeniedException("You are not responsible for this company profile.");
            }
            if (isManager && !isAdmin) {
                if (profile.getResponsibleManagerId() == null || !profile.getResponsibleManagerId().equals(currentUser.getId())) {
                    throw new org.springframework.security.access.AccessDeniedException("You are not responsible for this company profile.");
                }
            }
        } else {
            throw new org.springframework.security.access.AccessDeniedException("You are not responsible for this company profile.");
        }

        // Verification status (APPROVED vs UNVERIFIED) does not block authorized Manager direct profile editing

        // Optimistic concurrency / Stale write check
        CompanyProfileVersionHelper.VersionState currentVersion = CompanyProfileVersionHelper.resolveVersion(profile);
        if (request.getExpectedMajorVersion() == null || request.getExpectedRevision() == null
                || request.getExpectedMajorVersion() != currentVersion.majorVersion()
                || request.getExpectedRevision() != currentVersion.revision()) {
            throw new com.apms.common.exception.BusinessConflictException("The company profile has changed since you opened it. Refresh before saving.");
        }

        java.util.Map<String, Object> beforeSnapshot = versionService.createSnapshotMap(profile);
        CompanyProfile originalProfile = null;
        if (objectMapper != null && beforeSnapshot != null) {
            try {
                originalProfile = objectMapper.convertValue(beforeSnapshot, CompanyProfile.class);
            } catch (Exception e) {
                log.warn("Failed to create pre-edit backup of CompanyProfile {}: {}", profile.getId(), e.getMessage());
            }
        }

        // Apply Identity
        if (profile.getIdentity() == null) profile.setIdentity(new CompanyProfile.Identity());
        if (request.getLegalName() != null) profile.getIdentity().setLegalName(CompanyProfileDiffHelper.normalizeOptionalString(request.getLegalName()));
        if (request.getTradeName() != null) profile.getIdentity().setTradeName(CompanyProfileDiffHelper.normalizeOptionalString(request.getTradeName()));
        if (request.getTaxCode() != null) profile.getIdentity().setTaxCode(CompanyProfileDiffHelper.normalizeOptionalString(request.getTaxCode()));
        if (request.getRegistrationNumber() != null) profile.getIdentity().setRegistrationNumber(CompanyProfileDiffHelper.normalizeOptionalString(request.getRegistrationNumber()));

        // Apply Contact
        if (profile.getContact() == null) profile.setContact(new CompanyProfile.Contact());
        if (request.getWebsite() != null) profile.getContact().setWebsite(request.getWebsite());
        if (request.getEmails() != null) profile.getContact().setEmails(request.getEmails());
        if (request.getPhones() != null) profile.getContact().setPhones(request.getPhones());
        if (request.getAddressObjects() != null) {
            profile.getContact().setAddresses(request.getAddressObjects().isEmpty() ? null : request.getAddressObjects());
        } else if (request.getAddresses() != null) {
            if (request.getAddresses().isEmpty()) {
                profile.getContact().setAddresses(null);
            } else {
                List<CompanyProfile.Address> existingAddrs = profile.getContact().getAddresses();
                List<CompanyProfile.Address> mergedAddrs = new java.util.ArrayList<>();
                for (int i = 0; i < request.getAddresses().size(); i++) {
                    String fullAddr = request.getAddresses().get(i);
                    if (fullAddr == null || fullAddr.trim().isEmpty()) continue;
                    String trimmed = fullAddr.trim();
                    CompanyProfile.Address match = null;
                    if (existingAddrs != null) {
                        for (CompanyProfile.Address ea : existingAddrs) {
                            if (ea != null && trimmed.equalsIgnoreCase(ea.getFullAddress() != null ? ea.getFullAddress().trim() : "")) {
                                match = ea;
                                break;
                            }
                        }
                        if (match == null && i < existingAddrs.size() && existingAddrs.get(i) != null) {
                            match = existingAddrs.get(i);
                        }
                    }
                    CompanyProfile.Address.AddressBuilder builder = CompanyProfile.Address.builder().fullAddress(trimmed);
                    if (match != null) {
                        builder.type(match.getType() != null ? match.getType() : (i == 0 ? "HEADQUARTERS" : "BRANCH"));
                        builder.city(match.getCity());
                        builder.country(match.getCountry());
                    } else {
                        builder.type(i == 0 ? "HEADQUARTERS" : "BRANCH");
                    }
                    mergedAddrs.add(builder.build());
                }
                profile.getContact().setAddresses(mergedAddrs.isEmpty() ? null : mergedAddrs);
            }
        } else if (request.getAddress() != null) {
            if (StringUtils.hasText(request.getAddress())) {
                String trimmed = request.getAddress().trim();
                List<CompanyProfile.Address> existingAddrs = profile.getContact().getAddresses();
                CompanyProfile.Address match = (existingAddrs != null && !existingAddrs.isEmpty()) ? existingAddrs.get(0) : null;
                CompanyProfile.Address.AddressBuilder builder = CompanyProfile.Address.builder().fullAddress(trimmed);
                if (match != null) {
                    builder.type(match.getType() != null ? match.getType() : "HEADQUARTERS");
                    builder.city(match.getCity());
                    builder.country(match.getCountry());
                } else {
                    builder.type("HEADQUARTERS");
                }
                profile.getContact().setAddresses(java.util.List.of(builder.build()));
            } else {
                profile.getContact().setAddresses(null);
            }
        } else if (request.getHeadOfficeAddress() != null) {
            if (StringUtils.hasText(request.getHeadOfficeAddress())) {
                String trimmed = request.getHeadOfficeAddress().trim();
                List<CompanyProfile.Address> existingAddrs = profile.getContact().getAddresses();
                CompanyProfile.Address match = (existingAddrs != null && !existingAddrs.isEmpty()) ? existingAddrs.get(0) : null;
                CompanyProfile.Address.AddressBuilder builder = CompanyProfile.Address.builder()
                        .type("HEADQUARTERS")
                        .fullAddress(trimmed);
                if (match != null) {
                    builder.city(match.getCity());
                    builder.country(match.getCountry());
                }
                profile.getContact().setAddresses(java.util.List.of(builder.build()));
            } else {
                profile.getContact().setAddresses(null);
            }
        }

        // Apply Company Size
        if (profile.getCompanySize() == null) profile.setCompanySize(new CompanyProfile.CompanySize());
        if (request.getEmployeeTier() != null) profile.getCompanySize().setEmployeeTier(CompanyProfileDiffHelper.normalizeOptionalString(request.getEmployeeTier()));
        if (request.getEmployeeCount() != null) profile.getCompanySize().setEmployeeCount(request.getEmployeeCount());
        if (request.getRevenueTier() != null) profile.getCompanySize().setRevenueTier(CompanyProfileDiffHelper.normalizeOptionalString(request.getRevenueTier()));

        // Apply Business
        if (profile.getBusiness() == null) profile.setBusiness(new CompanyProfile.Business());
        if (request.getIndustries() != null) {
            List<String> industries = CompanyProfileDiffHelper.normalizeStringList(request.getIndustries());
            profile.getBusiness().setIndustries(industries.isEmpty() ? null : industries);
        }
        if (request.getMarkets() != null) {
            List<String> markets = CompanyProfileDiffHelper.normalizeStringList(request.getMarkets());
            profile.getBusiness().setMarkets(markets.isEmpty() ? null : markets);
        }
        if (request.getTargetCustomers() != null) {
            List<String> targets = CompanyProfileDiffHelper.normalizeStringList(request.getTargetCustomers());
            profile.getBusiness().setTargetCustomers(targets.isEmpty() ? null : targets);
        }
        if (request.getProducts() != null) {
            Set<String> seen = new java.util.HashSet<>();
            java.util.List<CompanyProfile.Product> prods = request.getProducts().stream()
                    .filter(p -> p != null && StringUtils.hasText(p.getName()))
                    .map(p -> p.getName().trim())
                    .filter(name -> !name.isEmpty())
                    .filter(name -> seen.add(name.toLowerCase(java.util.Locale.ROOT)))
                    .map(name -> CompanyProfile.Product.builder().name(name).build())
                    .collect(java.util.stream.Collectors.toList());
            profile.getBusiness().setProducts(prods);
        } else if (request.getProductsServices() != null) {
            Set<String> seen = new java.util.HashSet<>();
            java.util.List<CompanyProfile.Product> prods = request.getProductsServices().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .filter(name -> seen.add(name.toLowerCase(java.util.Locale.ROOT)))
                    .map(name -> CompanyProfile.Product.builder().name(name).build())
                    .collect(java.util.stream.Collectors.toList());
            profile.getBusiness().setProducts(prods.isEmpty() ? null : prods);
        }
        if (request.getBusinessModel() != null) profile.getBusiness().setBusinessModel(request.getBusinessModel());
        if (request.getFoundedYear() != null) {
            com.apms.domain.candidate.service.CandidateService.validateFoundedYear(request.getFoundedYear());
            profile.getBusiness().setFoundedYear(request.getFoundedYear());
        }
        if (request.getCompanyDescription() != null) profile.getBusiness().setCompanyDescription(request.getCompanyDescription());

        // Apply Leadership
        if (request.getCompanyMembers() != null) {
            List<CompanyProfile.CompanyMember> members = request.getCompanyMembers().stream()
                    .filter(CompanyProfileDiffHelper::isMeaningfulMember)
                    .map(m -> CompanyProfile.CompanyMember.builder()
                            .fullName(m.getFullName().trim())
                            .position(CompanyProfileDiffHelper.normalizeOptionalString(m.getPosition()))
                            .imageUrl(CompanyProfileDiffHelper.normalizeOptionalString(m.getImageUrl()))
                            .sourceUrl(CompanyProfileDiffHelper.normalizeOptionalString(m.getSourceUrl()))
                            .notes(CompanyProfileDiffHelper.normalizeOptionalString(m.getNotes()))
                            .researchedAt(m.getResearchedAt())
                            .researchedBy(m.getResearchedBy())
                            .taskId(m.getTaskId())
                            .build())
                    .collect(java.util.stream.Collectors.toList());
            profile.setCompanyMembers(members.isEmpty() ? null : members);
        }

        // Apply Tags
        if (request.getTags() != null) {
            List<String> tags = CompanyProfileDiffHelper.normalizeStringList(request.getTags());
            profile.setTags(tags.isEmpty() ? null : tags);
        }

        java.util.Map<String, Object> afterSnapshot = versionService.createSnapshotMap(profile);

        // Detect actual changes
        java.util.List<String> changedFieldPaths = new java.util.ArrayList<>();
        java.util.Map<String, Object> beforeValues = new java.util.HashMap<>();
        java.util.Map<String, Object> afterValues = new java.util.HashMap<>();

        String[] potentialPaths = new String[]{
                "identity.legalName", "identity.tradeName", "identity.taxCode", "identity.registrationNumber",
                "contact.website", "contact.emails", "contact.phones", "contact.addresses",
                "companySize.employeeTier", "companySize.employeeCount", "companySize.revenueTier",
                "business.industries", "business.markets", "business.targetCustomers", "business.products",
                "business.businessModel", "business.foundedYear", "business.companyDescription",
                "companyMembers", "tags"
        };

        for (String path : potentialPaths) {
            Object bVal = extractValueByPath(beforeSnapshot, path);
            Object aVal = extractValueByPath(afterSnapshot, path);
            if (!CompanyProfileDiffHelper.areValuesSemanticallyEqual(path, bVal, aVal)) {
                changedFieldPaths.add(path);
                beforeValues.put(path, CompanyProfileDiffHelper.normalizeForHistory(path, bVal));
                afterValues.put(path, CompanyProfileDiffHelper.normalizeForHistory(path, aVal));
            }
        }

        if (changedFieldPaths.isEmpty()) {
            return toResponse(profile);
        }

        profile.incrementMinorVersion();
        if (profile.getMetadata() == null) {
            profile.setMetadata(new CompanyProfile.Metadata());
        }
        profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        profile.getMetadata().setLastModifiedBy(currentUser != null ? String.valueOf(currentUser.getId()) : "SYSTEM");

        profileRepository.save(profile);

        CompanyProfileVersion savedVersion = null;
        try {
            savedVersion = versionService.createAndSaveVersion(
                    profile,
                    com.apms.domain.profile.enums.CompanyProfileChangeSource.MANAGER_MANUAL_EDIT,
                    changedFieldPaths,
                    beforeValues,
                    afterValues,
                    request.getChangeNote(),
                    "Manager Manual Edit (" + profile.getVersionLabel() + ")",
                    null,
                    null,
                    null,
                    null,
                    currentUser != null ? currentUser.getId() : null
            );
        } catch (Exception ex) {
            log.error("Failed to persist CompanyProfileVersion for profile {}: {}", profile.getId(), ex.getMessage(), ex);
            if (originalProfile != null) {
                try {
                    profileRepository.save(originalProfile);
                    log.info("Successfully rolled back profile {} to pre-edit state", profile.getId());
                } catch (Exception rollbackEx) {
                    log.error("CRITICAL: Failed to rollback profile {} after version creation failure: {}", profile.getId(), rollbackEx.getMessage(), rollbackEx);
                }
            }
            throw ex;
        }

        if (currentUser != null) {
            try {
                auditLogService.log(
                        currentUser.getId(),
                        AuditAction.COMPANY_PROFILE_UPDATED,
                        "CompanyProfile",
                        companyId,
                        "Profile updated by Manager (" + profile.getVersionLabel() + ", " + changedFieldPaths.size() + " fields changed)"
                );
            } catch (Exception e) {
                log.warn("Failed to write audit log for manager profile update on company {}: {}", companyId, e.getMessage());
            }
        }

        if (savedVersion != null) {
            String versionIdentity = StringUtils.hasText(savedVersion.getId()) ? savedVersion.getId() : profile.getVersionLabel();
            notificationService.notifyCompanyProfileUpdated(profile, versionIdentity, currentUser != null ? currentUser.getId() : null);
        }

        return toResponse(profile);
    }

    private Object extractValueByPath(java.util.Map<String, Object> map, String path) {
        if (map == null || path == null) return null;
        String[] parts = path.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (current instanceof java.util.Map) {
                current = ((java.util.Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    public ProfileResponse updateVisibility(String companyId, com.apms.domain.profile.dto.UpdateProfileVisibilityRequest request, Long actorId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .or(() -> profileRepository.findById(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("Company profile not found"));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("Company profile not found");
        }

        if (actorId != null) {
            boolean isAdmin = false;
            try {
                org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl user) {
                    isAdmin = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
                }
            } catch (Exception ignored) {}

            boolean isResponsibleManager = profile.getResponsibleManagerId() != null && profile.getResponsibleManagerId().equals(actorId);

            if (!isAdmin && !isResponsibleManager) {
                throw new org.springframework.security.access.AccessDeniedException("You are not authorized to manage visibility for this company profile.");
            }
        }

        boolean newIsHidden = request.getVisibility() == com.apms.common.enums.ProfileVisibility.HIDDEN;

        if (!newIsHidden) {
            validateMinimumPublishability(profile);
        }

        if (Boolean.valueOf(newIsHidden).equals(profile.getIsHidden())) {
            return toResponse(profile);
        }

        profile.setIsHidden(newIsHidden);
        profile = profileRepository.save(profile);

        AuditAction action = newIsHidden ? AuditAction.COMPANY_PROFILE_HIDDEN : AuditAction.COMPANY_PROFILE_PUBLISHED;
        auditLogService.log(actorId, action, "CompanyProfile", companyId, "Profile visibility updated to " + request.getVisibility());

        return toResponse(profile);
    }

    public Optional<Project> findOriginatingResearchNewCompanyProject(CompanyProfile profile) {
        if (profile == null) {
            return Optional.empty();
        }

        // Priority 1: Direct linkage from CompanyProfile.sourceRefs.projectIds
        if (profile.getSourceRefs() != null && profile.getSourceRefs().getProjectIds() != null && !profile.getSourceRefs().getProjectIds().isEmpty()) {
            Set<Long> sourceProjectIds = new LinkedHashSet<>();
            for (String pidStr : profile.getSourceRefs().getProjectIds()) {
                if (StringUtils.hasText(pidStr)) {
                    try {
                        sourceProjectIds.add(Long.parseLong(pidStr.trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (!sourceProjectIds.isEmpty()) {
                List<Project> sourceProjects = projectRepository.findAllById(sourceProjectIds);
                if (sourceProjects != null) {
                    List<Project> researchProjects = sourceProjects.stream()
                            .filter(p -> p.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY)
                            .toList();
                    if (!researchProjects.isEmpty()) {
                        if (researchProjects.size() == 1) {
                            return Optional.of(researchProjects.get(0));
                        }
                        // If multiple research projects linked in sourceRefs, prefer the one explicitly targeting this profile
                        String canonicalCompanyId = profile.getCompanyId();
                        Optional<Project> matchingTarget = researchProjects.stream()
                                .filter(p -> StringUtils.hasText(canonicalCompanyId) && canonicalCompanyId.equals(p.getTargetCompanyProfileId()))
                                .findFirst();
                        if (matchingTarget.isPresent()) {
                            return matchingTarget;
                        }
                        // Fallback tie-breaker among directly linked: earliest created / ID
                        return researchProjects.stream().min(Comparator.comparing(Project::getId));
                    }
                }
            }
        }

        // Priority 2: Direct linkage from Project.targetCompanyProfileId matching the exact CompanyProfile
        List<String> targetIds = new ArrayList<>();
        if (StringUtils.hasText(profile.getCompanyId())) {
            targetIds.add(profile.getCompanyId().trim());
        }
        if (StringUtils.hasText(profile.getId()) && !targetIds.contains(profile.getId().trim())) {
            targetIds.add(profile.getId().trim());
        }
        if (!targetIds.isEmpty()) {
            List<Project> byTarget = projectRepository.findByTargetCompanyProfileIdIn(targetIds);
            if (byTarget != null) {
                List<Project> targetResearchProjects = byTarget.stream()
                        .filter(p -> p.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY)
                        .toList();
                if (!targetResearchProjects.isEmpty()) {
                    if (targetResearchProjects.size() == 1) {
                        return Optional.of(targetResearchProjects.get(0));
                    }
                    return targetResearchProjects.stream().min(Comparator.comparing(Project::getId));
                }
            }
        }

        // Priority 3: Legacy fallback only when direct persisted linkage is unavailable
        if (profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getTaxCode())) {
            String normTax = profile.getIdentity().getTaxCode().replaceAll("[\\s\\-]", "").trim();
            List<Project> byTax = projectRepository.findActiveProjectsByTargetCompanyTaxCode(normTax);
            if (byTax != null) {
                List<Project> taxResearchProjects = byTax.stream()
                        .filter(p -> p.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY)
                        .toList();
                if (!taxResearchProjects.isEmpty()) {
                    return taxResearchProjects.stream().min(Comparator.comparing(Project::getId));
                }
            }
        }

        return Optional.empty();
    }

    public boolean appearsResearchCreated(CompanyProfile profile) {
        if (profile == null) return false;

        // Check if sourceRefs has research-originated markers
        if (profile.getSourceRefs() != null) {
            boolean hasProjectIds = profile.getSourceRefs().getProjectIds() != null && !profile.getSourceRefs().getProjectIds().isEmpty();
            boolean hasCandidateIds = profile.getSourceRefs().getCandidateIds() != null && !profile.getSourceRefs().getCandidateIds().isEmpty();
            boolean hasImportJobIds = profile.getSourceRefs().getImportJobIds() != null && !profile.getSourceRefs().getImportJobIds().isEmpty();
            boolean hasRawDocIds = profile.getSourceRefs().getRawDocumentIds() != null && !profile.getSourceRefs().getRawDocumentIds().isEmpty();
            if (hasProjectIds || hasCandidateIds || hasImportJobIds || hasRawDocIds) {
                return true;
            }
        }

        // Check unverified initial shell status
        if ("UNVERIFIED".equalsIgnoreCase(profile.getReviewStatus())) {
            return true;
        }

        // Check if any research project directly targets this profile
        List<String> targetIds = new ArrayList<>();
        if (StringUtils.hasText(profile.getCompanyId())) targetIds.add(profile.getCompanyId().trim());
        if (StringUtils.hasText(profile.getId()) && !targetIds.contains(profile.getId().trim())) targetIds.add(profile.getId().trim());
        if (!targetIds.isEmpty()) {
            List<Project> byTarget = projectRepository.findByTargetCompanyProfileIdIn(targetIds);
            if (byTarget != null && byTarget.stream().anyMatch(p -> p.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY)) {
                return true;
            }
        }

        return false;
    }

    public List<Project> findLinkedResearchNewCompanyProjects(CompanyProfile profile) {
        return findOriginatingResearchNewCompanyProject(profile)
                .map(List::of)
                .orElseGet(List::of);
    }

    public void validateMinimumPublishability(CompanyProfile profile) {
        String blockReason = resolvePublishBlockReason(profile, true);
        if (blockReason != null) {
            if (blockReason.contains("Legal Name and Tax Code")) {
                throw new com.apms.common.exception.BusinessValidationException("MISSING_LEGAL_NAME_OR_TAX_CODE", blockReason);
            } else if (blockReason.contains("New Company Research project is completed")) {
                throw new com.apms.common.exception.BusinessValidationException(
                        "NEW_COMPANY_PROJECT_NOT_COMPLETED",
                        "The company profile can only be published after the New Company Research project is completed."
                );
            } else {
                throw new com.apms.common.exception.BusinessValidationException("PUBLISH_BLOCKED", blockReason);
            }
        }
    }

    public boolean isPublishable(CompanyProfile p, String relType, boolean canManageVisibility) {
        return resolvePublishBlockReason(p, canManageVisibility) == null;
    }

    public String resolvePublishBlockReason(CompanyProfile profile, boolean canManageVisibility) {
        if (profile == null || Boolean.TRUE.equals(profile.getIsDeleted())) {
            return "Company profile not found or deleted.";
        }
        if (!canManageVisibility) {
            return "You do not have permission to manage visibility for this company profile.";
        }

        boolean hasLegalName = profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getLegalName());
        boolean hasTaxCode = profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getTaxCode());

        if (!hasLegalName || !hasTaxCode) {
            return "Profile requires Legal Name and Tax Code before it can be published.";
        }

        // Evaluate originating New Company Research project gate
        if (appearsResearchCreated(profile)) {
            Optional<Project> originatingProjectOpt = findOriginatingResearchNewCompanyProject(profile);
            if (originatingProjectOpt.isPresent()) {
                Project originatingProject = originatingProjectOpt.get();
                // Case A: Clearly linked to RESEARCH_NEW_COMPANY -> require status == COMPLETED
                if (originatingProject.getStatus() != ProjectStatus.COMPLETED) {
                    return "Available after the New Company Research project is completed.";
                }
            } else {
                // Case C: Appears research-created but originating project linkage cannot be verified
                return "Unable to verify completion of the originating New Company Research project.";
            }
        }
        // Case B: Pre-existing / canonical profile that did not originate from New Company Research shell -> gate passed

        return null;
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> getVisibilityManagementProfiles(
            String keyword,
            com.apms.common.enums.ProfileVisibility visibility,
            String eligibility,
            Long managerId,
            boolean isAdmin,
            Pageable pageable) {

        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
        List<CompanyProfile> manageable = resolveManageableProfiles(managerId, isAdmin, ownerCompanyId);

        if (StringUtils.hasText(keyword)) {
            String lowerKw = keyword.trim().toLowerCase();
            manageable = manageable.stream()
                    .filter(p -> {
                        String legal = p.getIdentity() != null && p.getIdentity().getLegalName() != null ? p.getIdentity().getLegalName().toLowerCase() : "";
                        String trade = p.getIdentity() != null && p.getIdentity().getTradeName() != null ? p.getIdentity().getTradeName().toLowerCase() : "";
                        return legal.contains(lowerKw) || trade.contains(lowerKw);
                    })
                    .toList();
        }

        if (visibility != null) {
            if (visibility == com.apms.common.enums.ProfileVisibility.HIDDEN) {
                manageable = manageable.stream().filter(p -> Boolean.TRUE.equals(p.getIsHidden())).toList();
            } else {
                manageable = manageable.stream().filter(p -> !Boolean.TRUE.equals(p.getIsHidden())).toList();
            }
        }

        List<ProfileResponse> mapped = manageable.stream()
                .map(this::toResponse)
                .toList();

        if (StringUtils.hasText(eligibility) && !"ALL".equalsIgnoreCase(eligibility)) {
            if ("ELIGIBLE".equalsIgnoreCase(eligibility)) {
                mapped = mapped.stream().filter(pr -> Boolean.TRUE.equals(pr.getCanPublish())).toList();
            } else if ("BLOCKED".equalsIgnoreCase(eligibility)) {
                mapped = mapped.stream().filter(pr -> !Boolean.TRUE.equals(pr.getCanPublish())).toList();
            }
        }

        mapped = new ArrayList<>(mapped);
        mapped.sort((a, b) -> {
            LocalDateTime ta = a.getMetadata() != null && a.getMetadata().getUpdatedAt() != null ? a.getMetadata().getUpdatedAt() : (a.getMetadata() != null ? a.getMetadata().getCreatedAt() : null);
            LocalDateTime tb = b.getMetadata() != null && b.getMetadata().getUpdatedAt() != null ? b.getMetadata().getUpdatedAt() : (b.getMetadata() != null ? b.getMetadata().getCreatedAt() : null);
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        int total = mapped.size();
        int fromIndex = (int) pageable.getOffset();
        if (fromIndex >= total) {
            return new org.springframework.data.domain.PageImpl<>(List.of(), pageable, total);
        }
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), total);
        List<ProfileResponse> pageContent = mapped.subList(fromIndex, toIndex);

        return new org.springframework.data.domain.PageImpl<>(pageContent, pageable, total);
    }

    @Transactional(readOnly = true)
    public com.apms.domain.profile.dto.ProfileVisibilitySummaryDto getVisibilitySummary(Long managerId, boolean isAdmin) {
        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
        List<CompanyProfile> manageable = resolveManageableProfiles(managerId, isAdmin, ownerCompanyId);

        long total = manageable.size();
        long published = manageable.stream().filter(p -> !Boolean.TRUE.equals(p.getIsHidden())).count();
        long hidden = manageable.stream().filter(p -> Boolean.TRUE.equals(p.getIsHidden())).count();
        long blocked = manageable.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsHidden()))
                .filter(p -> !isPublishable(p, null, true))
                .count();

        return com.apms.domain.profile.dto.ProfileVisibilitySummaryDto.builder()
                .totalProfiles(total)
                .published(published)
                .hidden(hidden)
                .blockedFromPublishing(blocked)
                .build();
    }

    /**
     * Resolves the set of CompanyProfiles visible to a manager in Profile Management.
     * Includes profiles currently managed (responsibleManagerId == managerId) AND
     * profiles historically managed (appearing in CompanyProfileManagerHistory).
     * Admin/Owner see all non-deleted, non-owner profiles.
     */
    private List<CompanyProfile> resolveManageableProfiles(Long managerId, boolean isAdmin, String ownerCompanyId) {
        List<CompanyProfile> allProfiles = profileRepository.findAll().stream()
                .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                .filter(p -> !ownerCompanyId.equals(p.getCompanyId()))
                .toList();

        if (isAdmin) {
            return allProfiles;
        }

        if (managerId == null) {
            return List.of();
        }

        // 1. Profiles currently managed
        Set<String> visibleProfileIds = new LinkedHashSet<>();
        Set<String> visibleCompanyIds = new LinkedHashSet<>();
        for (CompanyProfile p : allProfiles) {
            if (managerId.equals(p.getResponsibleManagerId())) {
                visibleProfileIds.add(p.getId());
                if (StringUtils.hasText(p.getCompanyId())) {
                    visibleCompanyIds.add(p.getCompanyId());
                }
            }
        }

        // 2. Profiles historically managed (from transfer history)
        List<com.apms.domain.profile.CompanyProfileManagerHistory> historyRecords =
                historyRepository.findByPreviousManagerAccountIdOrNewManagerAccountId(managerId, managerId);
        for (com.apms.domain.profile.CompanyProfileManagerHistory h : historyRecords) {
            if (StringUtils.hasText(h.getCompanyProfileId())) {
                visibleProfileIds.add(h.getCompanyProfileId());
            }
            if (StringUtils.hasText(h.getCompanyId())) {
                visibleCompanyIds.add(h.getCompanyId());
            }
        }

        // 3. Filter allProfiles to only those in the visible set
        return allProfiles.stream()
                .filter(p -> visibleProfileIds.contains(p.getId()) || (p.getCompanyId() != null && visibleCompanyIds.contains(p.getCompanyId())))
                .toList();
    }


    public static boolean isSupportedRelationship(String rel) {
        if (!StringUtils.hasText(rel)) return false;
        String normalized = rel.trim().toUpperCase();
        for (com.apms.common.enums.RelationshipType type : com.apms.common.enums.RelationshipType.values()) {
            if (type.name().equals(normalized)) return true;
        }
        return "PARTNER".equals(normalized) || "COMPETITOR".equals(normalized)
                || "SUPPLIER".equals(normalized) || "CUSTOMER".equals(normalized)
                || "POTENTIAL_PARTNER".equals(normalized);
    }

    /**
     * SYSTEM_ADMIN updates the Owner Organization's profile.
     * The Owner Company is resolved by the backend ({@link OwnerOrganizationService}),
     * never taken from the request body. Only whitelisted fields are applied.
     */
    @Transactional
    public ProfileResponse updateOwnerCompanyProfile(UpdateOwnerCompanyProfileRequest request) {
        CompanyProfile profile = ownerOrganizationService.getRequiredOwnerCompanyProfile();

        if (profile.getIdentity() == null) profile.setIdentity(new CompanyProfile.Identity());
        if (StringUtils.hasText(request.getLegalName())) profile.getIdentity().setLegalName(request.getLegalName());
        if (StringUtils.hasText(request.getTradeName())) profile.getIdentity().setTradeName(request.getTradeName());
        if (StringUtils.hasText(request.getTaxCode())) profile.getIdentity().setTaxCode(request.getTaxCode());
        if (StringUtils.hasText(request.getRegistrationNumber())) profile.getIdentity().setRegistrationNumber(request.getRegistrationNumber());
        if (StringUtils.hasText(request.getStockTicker())) profile.getIdentity().setStockTicker(request.getStockTicker());
        if (StringUtils.hasText(request.getStockExchange())) profile.getIdentity().setStockExchange(request.getStockExchange());

        if (profile.getBusiness() == null) profile.setBusiness(new CompanyProfile.Business());
        if (request.getIndustries() != null) profile.getBusiness().setIndustries(request.getIndustries());
        if (StringUtils.hasText(request.getBusinessModel())) profile.getBusiness().setBusinessModel(request.getBusinessModel());
        if (request.getMarkets() != null) profile.getBusiness().setMarkets(request.getMarkets());
        if (request.getTargetCustomers() != null) profile.getBusiness().setTargetCustomers(request.getTargetCustomers());
        if (request.getProducts() != null) {
            Set<String> seen = new java.util.HashSet<>();
            profile.getBusiness().setProducts(request.getProducts().stream()
                    .filter(product -> product != null && StringUtils.hasText(product.getName()))
                    .map(product -> product.getName().trim())
                    .filter(name -> !name.isEmpty())
                    .filter(name -> seen.add(name.toLowerCase(java.util.Locale.ROOT)))
                    .map(name -> CompanyProfile.Product.builder().name(name).build())
                    .toList());
        }
        if (request.getFoundedYear() != null) {
            com.apms.domain.candidate.service.CandidateService.validateFoundedYear(request.getFoundedYear());
            profile.getBusiness().setFoundedYear(request.getFoundedYear());
        }
        if (StringUtils.hasText(request.getCompanyDescription())) {
            profile.getBusiness().setCompanyDescription(request.getCompanyDescription());
        }

        if (request.getInsights() != null) {
            UpdateOwnerCompanyProfileRequest.SwotRequest swot = request.getInsights();
            if (profile.getInsights() == null) profile.setInsights(new CompanyProfile.Insights());
            if (swot.getStrengths() != null) profile.getInsights().setStrengths(swot.getStrengths());
            if (swot.getWeaknesses() != null) profile.getInsights().setWeaknesses(swot.getWeaknesses());
            if (swot.getOpportunities() != null) profile.getInsights().setOpportunities(swot.getOpportunities());
            if (swot.getThreats() != null) profile.getInsights().setThreats(swot.getThreats());
        }

        if (request.getCompanyMembers() != null) {
            profile.setCompanyMembers(request.getCompanyMembers().stream()
                    .map(member -> CompanyProfile.CompanyMember.builder()
                            .fullName(member.getFullName())
                            .position(member.getPosition())
                            .imageUrl(member.getImageUrl())
                            .sourceUrl(member.getSourceUrl())
                            .notes(member.getNotes())
                            .build())
                    .toList());
        }

        if (profile.getCompanySize() == null) profile.setCompanySize(new CompanyProfile.CompanySize());
        if (StringUtils.hasText(request.getEmployeeTier())) profile.getCompanySize().setEmployeeTier(request.getEmployeeTier());
        if (request.getEmployeeCount() != null) profile.getCompanySize().setEmployeeCount(request.getEmployeeCount());
        if (StringUtils.hasText(request.getRevenueTier())) profile.getCompanySize().setRevenueTier(request.getRevenueTier());

        if (profile.getContact() == null) profile.setContact(new CompanyProfile.Contact());
        if (StringUtils.hasText(request.getWebsite())) profile.getContact().setWebsite(request.getWebsite());

        List<String> emails = new java.util.ArrayList<>();
        if (StringUtils.hasText(request.getEmail())) emails.add(request.getEmail().trim());
        profile.getContact().setEmails(emails);

        List<String> phones = new java.util.ArrayList<>();
        if (StringUtils.hasText(request.getPhone())) phones.add(request.getPhone().trim());
        profile.getContact().setPhones(phones);

        if (request.getAddresses() != null) {
            profile.getContact().setAddresses(CompanyProfile.Contact.toAddressObjects(request.getAddresses()));
        } else if (request.getAddress() != null) {
            List<CompanyProfile.Address> addresses = new java.util.ArrayList<>();
            if (StringUtils.hasText(request.getAddress())) {
                addresses.add(CompanyProfile.Address.builder()
                        .type("HEADQUARTERS")
                        .fullAddress(request.getAddress().trim())
                        .build());
            }
            profile.getContact().setAddresses(addresses);
        }

        if (request.getTags() != null) profile.setTags(request.getTags());

        profile.incrementMinorVersion();

        if (profile.getMetadata() == null) {
            profile.setMetadata(CompanyProfile.Metadata.builder()
                    .createdBy("SYSTEM")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        } else {
            profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        Long currentUserId = getCurrentUserId();
        profile.getMetadata().setLastModifiedBy(currentUserId != null ? String.valueOf(currentUserId) : "SYSTEM");

        profileRepository.save(profile);

        if (currentUserId != null) {
            auditLogService.log(currentUserId, AuditAction.COMPANY_PROFILE_UPDATED, "CompanyProfile",
                    profile.getCompanyId(), "Owner company profile updated by SYSTEM_ADMIN");
        }

        return toResponse(profile);
    }

    /**
     * SYSTEM_ADMIN updates basic information of the canonical Owner Enterprise.
     * Only basic fields on Overview (Legal & Identity, Contact & Size, Introduction & Business Model)
     * are applied. If no fields changed, no version or audit log is created.
     */
    @Transactional
    public ProfileResponse updateAdminEnterpriseBasicInfo(AdminUpdateEnterpriseBasicInfoRequest request, UserDetailsImpl currentUser) {
        CompanyProfile profile = ownerOrganizationService.getRequiredOwnerCompanyProfile();

        // Optimistic concurrency / Stale write check
        CompanyProfileVersionHelper.VersionState currentVersion = CompanyProfileVersionHelper.resolveVersion(profile);
        if (request.getExpectedMajorVersion() != null && request.getExpectedRevision() != null) {
            if (request.getExpectedMajorVersion() != currentVersion.majorVersion()
                    || request.getExpectedRevision() != currentVersion.revision()) {
                throw new com.apms.common.exception.BusinessConflictException("The company profile has changed since you opened it. Refresh before saving.");
            }
        }

        java.util.Map<String, Object> beforeSnapshot = versionService.createSnapshotMap(profile);
        CompanyProfile originalProfile = null;
        if (objectMapper != null && beforeSnapshot != null) {
            try {
                originalProfile = objectMapper.convertValue(beforeSnapshot, CompanyProfile.class);
            } catch (Exception e) {
                log.warn("Failed to create pre-edit backup of Owner CompanyProfile {}: {}", profile.getId(), e.getMessage());
            }
        }

        // Apply only basic fields
        if (profile.getIdentity() == null) profile.setIdentity(new CompanyProfile.Identity());
        if (request.getTradeName() != null) profile.getIdentity().setTradeName(CompanyProfileDiffHelper.normalizeOptionalString(request.getTradeName()));
        if (request.getLegalName() != null) profile.getIdentity().setLegalName(CompanyProfileDiffHelper.normalizeOptionalString(request.getLegalName()));
        if (request.getTaxCode() != null) profile.getIdentity().setTaxCode(CompanyProfileDiffHelper.normalizeOptionalString(request.getTaxCode()));

        if (profile.getContact() == null) profile.setContact(new CompanyProfile.Contact());
        if (request.getWebsite() != null) profile.getContact().setWebsite(CompanyProfileDiffHelper.normalizeOptionalString(request.getWebsite()));

        if (request.getEmail() != null) {
            String cleanEmail = CompanyProfileDiffHelper.normalizeOptionalString(request.getEmail());
            profile.getContact().setEmails(cleanEmail != null ? List.of(cleanEmail) : null);
        }

        if (request.getPhone() != null) {
            String cleanPhone = CompanyProfileDiffHelper.normalizeOptionalString(request.getPhone());
            profile.getContact().setPhones(cleanPhone != null ? List.of(cleanPhone) : null);
        }

        if (request.getHeadOfficeAddress() != null) {
            String cleanAddr = CompanyProfileDiffHelper.normalizeOptionalString(request.getHeadOfficeAddress());
            if (cleanAddr != null) {
                CompanyProfile.Address addr = CompanyProfile.Address.builder()
                        .type("HEADQUARTERS")
                        .fullAddress(cleanAddr)
                        .build();
                profile.getContact().setAddresses(List.of(addr));
            } else {
                profile.getContact().setAddresses(null);
            }
        } else if (profile.getContact().getAddresses() != null) {
            List<CompanyProfile.Address> cleanAddrs = profile.getContact().getAddresses().stream()
                    .filter(CompanyProfileDiffHelper::isMeaningfulAddress)
                    .collect(java.util.stream.Collectors.toList());
            profile.getContact().setAddresses(cleanAddrs.isEmpty() ? null : cleanAddrs);
        }

        if (profile.getCompanySize() == null) profile.setCompanySize(new CompanyProfile.CompanySize());
        if (request.getEmployeeCount() != null) profile.getCompanySize().setEmployeeCount(request.getEmployeeCount());
        if (request.getEmployeeTier() != null) profile.getCompanySize().setEmployeeTier(CompanyProfileDiffHelper.normalizeOptionalString(request.getEmployeeTier()));

        if (profile.getBusiness() == null) profile.setBusiness(new CompanyProfile.Business());
        if (request.getBusinessModel() != null) profile.getBusiness().setBusinessModel(CompanyProfileDiffHelper.normalizeOptionalString(request.getBusinessModel()));

        java.util.Map<String, Object> afterSnapshot = versionService.createSnapshotMap(profile);

        // Detect actual changes across basic fields
        List<String> changedFieldPaths = new ArrayList<>();
        java.util.Map<String, Object> beforeValues = new java.util.HashMap<>();
        java.util.Map<String, Object> afterValues = new java.util.HashMap<>();

        String[] potentialPaths = new String[]{
                "identity.legalName", "identity.tradeName", "identity.taxCode",
                "contact.website", "contact.emails", "contact.phones", "contact.addresses",
                "companySize.employeeCount", "companySize.employeeTier",
                "business.businessModel"
        };

        for (String path : potentialPaths) {
            Object bVal = extractValueByPath(beforeSnapshot, path);
            Object aVal = extractValueByPath(afterSnapshot, path);
            if (!CompanyProfileDiffHelper.areValuesSemanticallyEqual(path, bVal, aVal)) {
                changedFieldPaths.add(path);
                beforeValues.put(path, CompanyProfileDiffHelper.normalizeForHistory(path, bVal));
                afterValues.put(path, CompanyProfileDiffHelper.normalizeForHistory(path, aVal));
            }
        }

        // If no changes, return existing response without creating version or audit log
        if (changedFieldPaths.isEmpty()) {
            return toResponse(profile);
        }

        profile.incrementMinorVersion();
        if (profile.getMetadata() == null) {
            profile.setMetadata(new CompanyProfile.Metadata());
        }
        profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        profile.getMetadata().setLastModifiedBy(currentUser != null ? String.valueOf(currentUser.getId()) : "SYSTEM");

        profileRepository.save(profile);

        try {
            versionService.createAndSaveVersion(
                    profile,
                    com.apms.domain.profile.enums.CompanyProfileChangeSource.ADMIN_MANUAL_EDIT,
                    changedFieldPaths,
                    beforeValues,
                    afterValues,
                    "Admin update of enterprise basic information",
                    "Admin Manual Edit",
                    null,
                    null,
                    null,
                    null,
                    currentUser != null ? currentUser.getId() : null
            );
        } catch (Exception ex) {
            log.error("Failed to persist CompanyProfileVersion for owner profile {}: {}", profile.getId(), ex.getMessage(), ex);
            if (originalProfile != null) {
                try {
                    profileRepository.save(originalProfile);
                    log.info("Successfully rolled back owner profile {} to pre-edit state", profile.getId());
                } catch (Exception rollbackEx) {
                    log.error("CRITICAL: Failed to rollback owner profile {} after version creation failure: {}", profile.getId(), rollbackEx.getMessage(), rollbackEx);
                }
            }
            throw ex;
        }

        if (currentUser != null) {
            try {
                auditLogService.log(
                        currentUser.getId(),
                        AuditAction.COMPANY_PROFILE_UPDATED,
                        "CompanyProfile",
                        profile.getCompanyId(),
                        "Admin updated enterprise basic information (" + changedFieldPaths.size() + " fields changed)"
                );
            } catch (Exception e) {
                log.warn("Failed to write audit log for admin enterprise basic update: {}", e.getMessage());
            }
        }

        return toResponse(profile);
    }

    /**
     * SYSTEM_ADMIN edits Business Fields of the canonical Owner Enterprise.
     * Strictly limited to industries, markets, targetCustomers, products.
     * Concurrency-safe, versioned as ADMIN_MANUAL_EDIT, and audited.
     */
    @Transactional
    public ProfileResponse updateAdminEnterpriseBusinessFields(AdminUpdateEnterpriseBusinessFieldsRequest request, UserDetailsImpl currentUser) {
        CompanyProfile profile = ownerOrganizationService.getRequiredOwnerCompanyProfile();

        // Optimistic concurrency / Stale write check
        CompanyProfileVersionHelper.VersionState currentVersion = CompanyProfileVersionHelper.resolveVersion(profile);
        if (request.getExpectedMajorVersion() != null && request.getExpectedRevision() != null) {
            if (request.getExpectedMajorVersion() != currentVersion.majorVersion()
                    || request.getExpectedRevision() != currentVersion.revision()) {
                throw new com.apms.common.exception.BusinessConflictException("The company profile has changed since you opened it. Refresh before saving.");
            }
        }

        java.util.Map<String, Object> beforeSnapshot = versionService.createSnapshotMap(profile);
        CompanyProfile originalProfile = null;
        if (objectMapper != null && beforeSnapshot != null) {
            try {
                originalProfile = objectMapper.convertValue(beforeSnapshot, CompanyProfile.class);
            } catch (Exception e) {
                log.warn("Failed to create pre-edit backup of Owner CompanyProfile {}: {}", profile.getId(), e.getMessage());
            }
        }

        if (profile.getBusiness() == null) profile.setBusiness(new CompanyProfile.Business());
        if (request.getIndustries() != null) {
            profile.getBusiness().setIndustries(request.getIndustries().stream()
                    .map(s -> s != null ? s.trim() : "")
                    .filter(StringUtils::hasText)
                    .collect(java.util.stream.Collectors.toList()));
        }
        if (request.getMarkets() != null) {
            profile.getBusiness().setMarkets(request.getMarkets().stream()
                    .map(s -> s != null ? s.trim() : "")
                    .filter(StringUtils::hasText)
                    .collect(java.util.stream.Collectors.toList()));
        }
        if (request.getTargetCustomers() != null) {
            profile.getBusiness().setTargetCustomers(request.getTargetCustomers().stream()
                    .map(s -> s != null ? s.trim() : "")
                    .filter(StringUtils::hasText)
                    .collect(java.util.stream.Collectors.toList()));
        }
        if (request.getProducts() != null) {
            Set<String> seen = new java.util.HashSet<>();
            List<CompanyProfile.Product> prods = request.getProducts().stream()
                    .filter(p -> p != null && StringUtils.hasText(p.getName()))
                    .map(p -> p.getName().trim())
                    .filter(name -> !name.isEmpty())
                    .filter(name -> seen.add(name.toLowerCase(java.util.Locale.ROOT)))
                    .map(name -> CompanyProfile.Product.builder().name(name).build())
                    .collect(java.util.stream.Collectors.toList());
            profile.getBusiness().setProducts(prods);
        }

        java.util.Map<String, Object> afterSnapshot = versionService.createSnapshotMap(profile);

        List<String> changedFieldPaths = new ArrayList<>();
        java.util.Map<String, Object> beforeValues = new java.util.HashMap<>();
        java.util.Map<String, Object> afterValues = new java.util.HashMap<>();

        String[] potentialPaths = new String[]{
                "business.industries", "business.markets", "business.targetCustomers", "business.products"
        };

        for (String path : potentialPaths) {
            Object bVal = extractValueByPath(beforeSnapshot, path);
            Object aVal = extractValueByPath(afterSnapshot, path);
            if (!CompanyProfileDiffHelper.areValuesSemanticallyEqual(path, bVal, aVal)) {
                changedFieldPaths.add(path);
                beforeValues.put(path, CompanyProfileDiffHelper.normalizeForHistory(path, bVal));
                afterValues.put(path, CompanyProfileDiffHelper.normalizeForHistory(path, aVal));
            }
        }

        if (changedFieldPaths.isEmpty()) {
            return toResponse(profile);
        }

        profile.incrementMinorVersion();
        if (profile.getMetadata() == null) {
            profile.setMetadata(new CompanyProfile.Metadata());
        }
        profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        profile.getMetadata().setLastModifiedBy(currentUser != null ? String.valueOf(currentUser.getId()) : "SYSTEM");

        profileRepository.save(profile);

        try {
            versionService.createAndSaveVersion(
                    profile,
                    com.apms.domain.profile.enums.CompanyProfileChangeSource.ADMIN_MANUAL_EDIT,
                    changedFieldPaths,
                    beforeValues,
                    afterValues,
                    "Admin update of enterprise business fields",
                    "Admin Manual Edit",
                    null,
                    null,
                    null,
                    null,
                    currentUser != null ? currentUser.getId() : null
            );
        } catch (Exception ex) {
            log.error("Failed to persist CompanyProfileVersion for owner profile {}: {}", profile.getId(), ex.getMessage(), ex);
            if (originalProfile != null) {
                try {
                    profileRepository.save(originalProfile);
                    log.info("Successfully rolled back owner profile {} to pre-edit state", profile.getId());
                } catch (Exception rollbackEx) {
                    log.error("CRITICAL: Failed to rollback owner profile {} after version creation failure: {}", profile.getId(), rollbackEx.getMessage(), rollbackEx);
                }
            }
            throw ex;
        }

        if (currentUser != null) {
            try {
                auditLogService.log(
                        currentUser.getId(),
                        AuditAction.COMPANY_PROFILE_UPDATED,
                        "CompanyProfile",
                        profile.getCompanyId(),
                        "Admin updated enterprise business fields (" + changedFieldPaths.size() + " fields changed)"
                );
            } catch (Exception e) {
                log.warn("Failed to write audit log for admin enterprise business fields update: {}", e.getMessage());
            }
        }

        return toResponse(profile);
    }

    /**
     * SYSTEM_ADMIN manages Leadership members of the canonical Owner Enterprise.
     * Concurrency-safe, server-side metadata protection, versioned as ADMIN_MANUAL_EDIT, and audited.
     */
    @Transactional
    public ProfileResponse updateAdminEnterpriseLeadership(AdminUpdateEnterpriseLeadershipRequest request, UserDetailsImpl currentUser) {
        CompanyProfile profile = ownerOrganizationService.getRequiredOwnerCompanyProfile();

        // Optimistic concurrency / Stale write check
        CompanyProfileVersionHelper.VersionState currentVersion = CompanyProfileVersionHelper.resolveVersion(profile);
        if (request.getExpectedMajorVersion() != null && request.getExpectedRevision() != null) {
            if (request.getExpectedMajorVersion() != currentVersion.majorVersion()
                    || request.getExpectedRevision() != currentVersion.revision()) {
                throw new com.apms.common.exception.BusinessConflictException("The company profile has changed since you opened it. Refresh before saving.");
            }
        }

        java.util.Map<String, Object> beforeSnapshot = versionService.createSnapshotMap(profile);
        CompanyProfile originalProfile = null;
        if (objectMapper != null && beforeSnapshot != null) {
            try {
                originalProfile = objectMapper.convertValue(beforeSnapshot, CompanyProfile.class);
            } catch (Exception e) {
                log.warn("Failed to create pre-edit backup of Owner CompanyProfile {}: {}", profile.getId(), e.getMessage());
            }
        }

        List<CompanyProfile.CompanyMember> existingMembers = profile.getCompanyMembers() != null ? profile.getCompanyMembers() : new ArrayList<>();
        java.util.Map<String, CompanyProfile.CompanyMember> existingMap = new java.util.HashMap<>();
        for (CompanyProfile.CompanyMember m : existingMembers) {
            if (m.getFullName() != null) {
                existingMap.put(m.getFullName().trim().toLowerCase(), m);
            }
        }

        List<CompanyProfile.CompanyMember> updatedMembers = new ArrayList<>();
        if (request.getMembers() != null) {
            for (AdminEnterpriseLeadershipMemberRequest mReq : request.getMembers()) {
                if (mReq == null || !StringUtils.hasText(mReq.getFullName())) {
                    continue;
                }
                String key = mReq.getFullName().trim().toLowerCase();
                CompanyProfile.CompanyMember existing = existingMap.get(key);

                CompanyProfile.CompanyMember newMember = CompanyProfile.CompanyMember.builder()
                        .fullName(mReq.getFullName().trim())
                        .position(StringUtils.hasText(mReq.getPosition()) ? mReq.getPosition().trim() : "")
                        .imageUrl(StringUtils.hasText(mReq.getImageUrl()) ? mReq.getImageUrl().trim() : null)
                        .sourceUrl(StringUtils.hasText(mReq.getSourceUrl()) ? mReq.getSourceUrl().trim() : null)
                        .notes(StringUtils.hasText(mReq.getNotes()) ? mReq.getNotes().trim() : null)
                        .researchedAt(existing != null && existing.getResearchedAt() != null ? existing.getResearchedAt() : LocalDateTime.now())
                        .researchedBy(existing != null && existing.getResearchedBy() != null ? existing.getResearchedBy() : (currentUser != null ? currentUser.getId() : null))
                        .taskId(existing != null ? existing.getTaskId() : null)
                        .build();
                updatedMembers.add(newMember);
            }
        }
        profile.setCompanyMembers(updatedMembers);

        java.util.Map<String, Object> afterSnapshot = versionService.createSnapshotMap(profile);

        List<String> changedFieldPaths = new ArrayList<>();
        java.util.Map<String, Object> beforeValues = new java.util.HashMap<>();
        java.util.Map<String, Object> afterValues = new java.util.HashMap<>();

        Object bMembers = extractValueByPath(beforeSnapshot, "companyMembers");
        Object aMembers = extractValueByPath(afterSnapshot, "companyMembers");
        if (!CompanyProfileDiffHelper.areValuesSemanticallyEqual("companyMembers", bMembers, aMembers)) {
            changedFieldPaths.add("companyMembers");
            beforeValues.put("companyMembers", CompanyProfileDiffHelper.normalizeForHistory("companyMembers", bMembers));
            afterValues.put("companyMembers", CompanyProfileDiffHelper.normalizeForHistory("companyMembers", aMembers));
        }

        if (changedFieldPaths.isEmpty()) {
            return toResponse(profile);
        }

        profile.incrementMinorVersion();
        if (profile.getMetadata() == null) {
            profile.setMetadata(new CompanyProfile.Metadata());
        }
        profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        profile.getMetadata().setLastModifiedBy(currentUser != null ? String.valueOf(currentUser.getId()) : "SYSTEM");

        profileRepository.save(profile);

        try {
            versionService.createAndSaveVersion(
                    profile,
                    com.apms.domain.profile.enums.CompanyProfileChangeSource.ADMIN_MANUAL_EDIT,
                    changedFieldPaths,
                    beforeValues,
                    afterValues,
                    "Admin update of enterprise leadership",
                    "Admin Manual Edit",
                    null,
                    null,
                    null,
                    null,
                    currentUser != null ? currentUser.getId() : null
            );
        } catch (Exception ex) {
            log.error("Failed to persist CompanyProfileVersion for owner profile {}: {}", profile.getId(), ex.getMessage(), ex);
            if (originalProfile != null) {
                try {
                    profileRepository.save(originalProfile);
                    log.info("Successfully rolled back owner profile {} to pre-edit state", profile.getId());
                } catch (Exception rollbackEx) {
                    log.error("CRITICAL: Failed to rollback owner profile {} after version creation failure: {}", profile.getId(), rollbackEx.getMessage(), rollbackEx);
                }
            }
            throw ex;
        }

        if (currentUser != null) {
            try {
                auditLogService.log(
                        currentUser.getId(),
                        AuditAction.COMPANY_PROFILE_UPDATED,
                        "CompanyProfile",
                        profile.getCompanyId(),
                        "Admin updated enterprise leadership (" + updatedMembers.size() + " members)"
                );
            } catch (Exception e) {
                log.warn("Failed to write audit log for admin enterprise leadership update: {}", e.getMessage());
            }
        }

        return toResponse(profile);
    }

    /**
     * SYSTEM_ADMIN creates or updates one financial statement (reportType + reportYear)
     * of the Owner Organization. The record is identified by (reportType, reportYear);
     * saving the same pair replaces the previous value and never touches other years.
     */
    @Transactional
    public ProfileResponse upsertOwnerFinancialReport(FinancialReportRequest request) {
        CompanyProfile profile = ownerOrganizationService.getRequiredOwnerCompanyProfile();
        validateFinancialReport(request);

        List<CompanyProfile.FinancialReport> reports = new ArrayList<>(
                profile.getFinancialReports() != null ? profile.getFinancialReports() : List.of());
        reports.removeIf(existing -> existing.getReportType() != null
                && existing.getReportType().equalsIgnoreCase(request.getReportType())
                && Objects.equals(existing.getReportYear(), request.getReportYear()));
        reports.add(toFinancialReport(request));

        profile.setFinancialReports(reports);
        return saveOwnerProfileWithAudit(profile);
    }

    /**
     * SYSTEM_ADMIN deletes one financial statement (reportType + reportYear) of the
     * Owner Organization. Other years and report types are preserved.
     */
    @Transactional
    public ProfileResponse deleteOwnerFinancialReport(String reportType, int reportYear) {
        CompanyProfile profile = ownerOrganizationService.getRequiredOwnerCompanyProfile();
        List<CompanyProfile.FinancialReport> reports = new ArrayList<>(
                profile.getFinancialReports() != null ? profile.getFinancialReports() : List.of());
        boolean removed = reports.removeIf(existing -> existing.getReportType() != null
                && existing.getReportType().equalsIgnoreCase(reportType)
                && Objects.equals(existing.getReportYear(), reportYear));
        if (!removed) {
            throw new BusinessValidationException("Không tìm thấy báo cáo tài chính " + reportType + " năm " + reportYear);
        }

        profile.setFinancialReports(reports);
        return saveOwnerProfileWithAudit(profile);
    }

    private CompanyProfile.FinancialReport toFinancialReport(FinancialReportRequest request) {
        return CompanyProfile.FinancialReport.builder()
                .reportType(request.getReportType().trim().toUpperCase())
                .periodType(StringUtils.hasText(request.getPeriodType()) ? request.getPeriodType() : "YEAR")
                .reportYear(request.getReportYear())
                .reportPeriod(StringUtils.hasText(request.getReportPeriod())
                        ? request.getReportPeriod()
                        : String.valueOf(request.getReportYear()))
                .itemsJson(request.getItemsJson())
                .sourceUrl(request.getSourceUrl())
                .build();
    }

    /**
     * Validates a financial report payload. Beyond basic presence checks, the
     * itemsJson must be a parseable single-period FinancialDocument whose values
     * are numeric, so the frontend table can always render it.
     */
    private void validateFinancialReport(FinancialReportRequest request) {
        String reportType = request.getReportType() == null ? "" : request.getReportType().trim().toUpperCase();
        Set<String> allowed = Set.of("SUMMARY", "BALANCE_SHEET", "INCOME_STATEMENT", "CASH_FLOW", "RATIOS", "PLAN", "CHISO");
        if (!allowed.contains(reportType)) {
            throw new BusinessValidationException("Loại báo cáo tài chính không hợp lệ: " + request.getReportType());
        }
        if (request.getReportYear() == null) {
            throw new BusinessValidationException("Năm tài chính không được để trống");
        }
        if (request.getReportYear() < 1900 || request.getReportYear() > 2100) {
            throw new BusinessValidationException("Năm tài chính không hợp lệ: " + request.getReportYear());
        }
        if (!StringUtils.hasText(request.getItemsJson())) {
            throw new BusinessValidationException("Dữ liệu báo cáo tài chính không được để trống");
        }
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode root = mapper.readTree(request.getItemsJson());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new BusinessValidationException("Dữ liệu báo cáo tài chính không hợp lệ: thiếu bảng dữ liệu");
            }
            JsonNode table = data.get(0).path("data");
            if (!table.isArray() || table.isEmpty()) {
                throw new BusinessValidationException("Dữ liệu báo cáo tài chính không hợp lệ: thiếu kỳ báo cáo");
            }
            boolean hasPeriod = false;
            for (JsonNode period : table) {
                if (period.path("time").isValueNode()) {
                    hasPeriod = true;
                }
                JsonNode values = period.path("data");
                if (values.isArray()) {
                    for (JsonNode item : values) {
                        JsonNode value = item.get("value");
                        if (value != null && !value.isNull() && !value.isNumber()) {
                            throw new BusinessValidationException(
                                    "Dữ liệu báo cáo tài chính không hợp lệ: giá trị phải là số");
                        }
                    }
                }
            }
            if (!hasPeriod) {
                throw new BusinessValidationException("Dữ liệu báo cáo tài chính không hợp lệ: thiếu kỳ báo cáo");
            }
        } catch (BusinessValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessValidationException("Dữ liệu báo cáo tài chính không hợp lệ: " + e.getMessage());
        }
    }

    private ProfileResponse saveOwnerProfileWithAudit(CompanyProfile profile) {
        profile.incrementMinorVersion();

        if (profile.getMetadata() == null) {
            profile.setMetadata(CompanyProfile.Metadata.builder()
                    .createdBy("SYSTEM")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        } else {
            profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        Long currentUserId = getCurrentUserId();
        profile.getMetadata().setLastModifiedBy(currentUserId != null ? String.valueOf(currentUserId) : "SYSTEM");

        profileRepository.save(profile);

        if (currentUserId != null) {
            auditLogService.log(currentUserId, AuditAction.COMPANY_PROFILE_UPDATED, "CompanyProfile",
                    profile.getCompanyId(), "Owner company financial report updated by SYSTEM_ADMIN");
        }

        return toResponse(profile);
    }

    @Transactional
    public void deleteProfile(String companyId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found"));

        if (!Boolean.TRUE.equals(profile.getIsDeleted())) {
            profile.setIsDeleted(true);
            profile.getMetadata().setDeletedAt(LocalDateTime.now());
            profileRepository.save(profile);

            Long currentUserId = getCurrentUserId();
            if (currentUserId != null) {
                auditLogService.log(currentUserId, AuditAction.COMPANY_PROFILE_ARCHIVED, "CompanyProfile", companyId, "Profile archived/soft-deleted");
            }
        }
    }

    private Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
                return ((UserDetailsImpl) auth.getPrincipal()).getId();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ─────────────────────────────────────────────
    // MAPPERS
    // ─────────────────────────────────────────────

    ProfileResponse toResponse(CompanyProfile p) {
        com.apms.common.enums.ProfileVisibility visibility = Boolean.TRUE.equals(p.getIsHidden())
                ? com.apms.common.enums.ProfileVisibility.HIDDEN
                : com.apms.common.enums.ProfileVisibility.PUBLISHED;

        CompanyProfileVersionHelper.VersionState versionState = CompanyProfileVersionHelper.resolveVersion(p);
        int major = versionState.majorVersion();
        int rev = versionState.revision();
        String versionLabel = versionState.versionLabel();
        String legacyVersion = p.getVersion() != null ? p.getVersion() : versionState.legacyVersion();

        if (profileVersionRepository != null && p != null) {
            try {
                String profileId = p.getId();
                String compId = p.getCompanyId();
                java.util.List<com.apms.domain.profile.CompanyProfileVersion> versions = compId != null && !compId.isBlank()
                        ? profileVersionRepository.findByCompanyProfileIdOrCompanyIdOrderByCreatedAtDesc(profileId, compId)
                        : profileVersionRepository.findByCompanyProfileIdOrderByCreatedAtDesc(profileId);
                if (versions != null && !versions.isEmpty()) {
                    com.apms.domain.profile.CompanyProfileVersion latestVer = versions.get(0);
                    int latestMajor = latestVer.getMajorVersion() != null
                            ? latestVer.getMajorVersion()
                            : CompanyProfileVersionHelper.parseLegacyVersion(latestVer.getVersion())[0];
                    int latestRev = latestVer.getRevision() != null
                            ? latestVer.getRevision()
                            : CompanyProfileVersionHelper.parseLegacyVersion(latestVer.getVersion())[1];

                    if (latestMajor > major || (latestMajor == major && latestRev > rev)) {
                        major = latestMajor;
                        rev = latestRev;
                        versionLabel = CompanyProfileVersionHelper.formatVersionLabel(major, rev);
                        legacyVersion = CompanyProfileVersionHelper.formatLegacyVersion(major, rev);

                        p.setMajorVersion(major);
                        p.setRevision(rev);
                        p.setVersion(legacyVersion);
                        profileRepository.save(p);
                    }
                }
            } catch (Exception ignored) {}
        }

        boolean canEdit = false;
        boolean canManageVisibility = false;
        boolean canAccessRelationship = false;
        boolean canTransferManagement = false;
        boolean isCurrentResponsibleManager = false;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl user) {
                boolean isAdmin = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
                boolean isOwner = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_OWNER"));
                boolean isManager = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_MANAGER"));
                boolean isResponsibleManager = isManager && p.getResponsibleManagerId() != null && p.getResponsibleManagerId().equals(user.getId());

                // isCurrentResponsibleManager reflects ownership identity only: authenticated account ID == responsibleManagerId
                isCurrentResponsibleManager = Objects.equals(p.getResponsibleManagerId(), user.getId());

                if (isAdmin || isResponsibleManager) {
                    canManageVisibility = true;
                    if (!Boolean.TRUE.equals(p.getIsDeleted())) {
                        canEdit = true;
                    }
                }

                if (isAdmin || isOwner) {
                    canTransferManagement = true;
                } else if (isResponsibleManager) {
                    canTransferManagement = true;
                }

                canAccessRelationship = relationshipClosenessAccessEvaluator.canAccess(p, user);
            }
        } catch (Exception ignored) {}

        String relType = resolveRelationshipType(p.getCompanyId());
        boolean canPublish = isPublishable(p, relType, canManageVisibility);
        String publishBlockReason = resolvePublishBlockReason(p, canManageVisibility);
        String responsibleManagerName = resolveDisplayName(p.getResponsibleManagerId());

        return ProfileResponse.builder()
                .id(p.getId())
                .companyId(p.getCompanyId())
                .identity(p.getIdentity())
                .business(p.getBusiness())
                .companySize(p.getCompanySize())
                .contact(p.getContact())
                .insights(p.getInsights())
                .financial(p.getFinancial())
                .market(p.getMarket())
                .innovation(p.getInnovation())
                .risk(p.getRisk())
                .compliance(p.getCompliance())
                .companyMembers(p.getCompanyMembers())
                .reviewStatus(p.getReviewStatus())
                .visibility(visibility)
                .isHidden(p.getIsHidden())
                .relationshipType(relType)
                .tags(p.getTags())
                .metadata(p.getMetadata())
                .version(legacyVersion)
                .majorVersion(major)
                .revision(rev)
                .versionLabel(versionLabel)
                .responsibleManagerId(p.getResponsibleManagerId())
                .responsibleManagerName(responsibleManagerName)
                .canEditProfile(canEdit)
                .canManageVisibility(canManageVisibility)
                .canPublish(canPublish)
                .publishBlockReason(publishBlockReason)
                .canAccessRelationshipCloseness(canAccessRelationship)
                .canTransferManagement(canTransferManagement)
                .isCurrentResponsibleManager(isCurrentResponsibleManager)
                .build();
    }

    public String resolveDisplayName(Long accountId) {
        if (accountId == null) return null;
        return userProfileRepository.findByAccountId(accountId)
                .map(profile -> ((profile.getFirstName() != null ? profile.getFirstName() : "") + " " + (profile.getLastName() != null ? profile.getLastName() : "")).trim())
                .filter(StringUtils::hasText)
                .orElseGet(() -> accountRepository.findById(accountId)
                        .map(com.apms.domain.user.Account::getEmail)
                        .orElse("Account #" + accountId));
    }

    public CompanyProfile findProfileByProfileIdOrThrow(String companyProfileId) {
        return profileRepository.findById(companyProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("Company profile not found with id: " + companyProfileId));
    }

    public void transferResponsibility(String companyProfileId, com.apms.domain.profile.dto.TransferResponsibilityRequest request, UserDetailsImpl currentUser) {
        CompanyProfile profile = findProfileByProfileIdOrThrow(companyProfileId);

        // 1. validate actor
        boolean isAdmin = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
        boolean isOwner = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_OWNER"));
        if (profile.getResponsibleManagerId() == null) {
            if (!isAdmin && !isOwner) {
                throw new org.springframework.security.access.AccessDeniedException("Only SYSTEM_ADMIN or BUSINESS_OWNER can assign responsibility to an unassigned profile");
            }
        } else {
            if (!isAdmin && !isOwner && !currentUser.getId().equals(profile.getResponsibleManagerId())) {
                throw new org.springframework.security.access.AccessDeniedException("Only the current responsible Manager, BUSINESS_OWNER, or SYSTEM_ADMIN can transfer responsibility");
            }
        }

        // 2. validate target manager
        if (request == null || request.getNewManagerAccountId() == null) {
            throw new BusinessValidationException("Target manager account ID is required");
        }
        com.apms.domain.user.Account target = accountRepository.findById(request.getNewManagerAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Target manager not found with id: " + request.getNewManagerAccountId()));

        if (!Boolean.TRUE.equals(target.getIsActive()) || 
            !target.getRoles().contains(com.apms.common.enums.SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            throw new BusinessValidationException("Target account must be an active BUSINESS_DEVELOPMENT_MANAGER");
        }

        if (Objects.equals(profile.getResponsibleManagerId(), target.getId())) {
            throw new BusinessValidationException("Target manager is already the responsible manager for this company profile");
        }

        if (!StringUtils.hasText(request.getReason())) {
            throw new BusinessValidationException("Reason for transfer is required");
        }

        // 3. validate current ownership / concurrency
        if (request.getExpectedCurrentManagerAccountId() != null) {
            if (!Objects.equals(request.getExpectedCurrentManagerAccountId(), profile.getResponsibleManagerId())) {
                throw new com.apms.common.exception.BusinessConflictException(
                        "Company profile responsible manager has been concurrently updated. Expected: "
                                + request.getExpectedCurrentManagerAccountId() + ", current: " + profile.getResponsibleManagerId());
            }
        }

        // 3b. block transfer if non-terminal project exists targeting this company profile
        if (StringUtils.hasText(profile.getCompanyId())) {
            List<ProjectStatus> terminalStatuses = List.of(
                    ProjectStatus.COMPLETED, ProjectStatus.CLOSED,
                    ProjectStatus.CANCELLED, ProjectStatus.ARCHIVED);
            boolean hasActiveProject = projectRepository.existsByTargetCompanyProfileIdAndStatusNotIn(
                    profile.getCompanyId().trim(), terminalStatuses);
            if (hasActiveProject) {
                throw new com.apms.common.exception.BusinessConflictException(
                        "Management cannot be transferred while this company has an active project. Close or complete the project first.");
            }
        }

        // 4. update CompanyProfile.responsibleManagerId
        Long previousManagerId = profile.getResponsibleManagerId();
        profile.setResponsibleManagerId(target.getId());
        profileRepository.save(profile);

        // 5. create immutable CompanyProfileManagerHistory
        String previousManagerName = resolveDisplayName(previousManagerId);
        String newManagerName = resolveDisplayName(target.getId());
        String actorName = resolveDisplayName(currentUser.getId());

        com.apms.domain.profile.CompanyProfileManagerHistory history = com.apms.domain.profile.CompanyProfileManagerHistory.builder()
                .companyProfileId(profile.getId())
                .companyId(profile.getCompanyId())
                .previousManagerAccountId(previousManagerId)
                .previousManagerDisplayName(previousManagerName)
                .newManagerAccountId(target.getId())
                .newManagerDisplayName(newManagerName)
                .transferredByAccountId(currentUser.getId())
                .transferredByDisplayName(actorName)
                .reason(request.getReason().trim())
                .transferredAt(LocalDateTime.now())
                .build();
        historyRepository.save(history);

        // 6. write AuditLog
        try {
            auditLogService.log(
                    currentUser.getId(),
                    com.apms.common.enums.AuditAction.COMPANY_PROFILE_RESPONSIBILITY_TRANSFERRED,
                    "CompanyProfile",
                    profile.getId(),
                    "Responsibility transferred from manager " + (previousManagerId != null ? previousManagerId : "unassigned")
                            + " to manager " + target.getId() + ". Reason: " + request.getReason().trim()
            );
        } catch (Exception e) {
            log.error("Failed to write audit log for manager transfer: {}", e.getMessage(), e);
        }
    }

    public List<com.apms.domain.profile.dto.CompanyProfileManagerHistoryDto> getManagementHistory(String companyProfileId, UserDetailsImpl currentUser) {
        CompanyProfile profile = findProfileByProfileIdOrThrow(companyProfileId);

        boolean isAdmin = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
        boolean isOwner = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_OWNER"));
        boolean isManager = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_MANAGER"));
        if (!isAdmin && !isOwner) {
            if (!isManager) {
                throw new org.springframework.security.access.AccessDeniedException("Unauthorized to view management history");
            }
            // BD Manager: must be current responsible manager OR a former manager (present in history)
            boolean isCurrentManager = currentUser.getId().equals(profile.getResponsibleManagerId());
            if (!isCurrentManager) {
                List<com.apms.domain.profile.CompanyProfileManagerHistory> historyRecords =
                        historyRepository.findByCompanyProfileIdOrderByTransferredAtDesc(profile.getId());
                boolean isFormerManager = historyRecords.stream().anyMatch(h ->
                        currentUser.getId().equals(h.getPreviousManagerAccountId()) ||
                        currentUser.getId().equals(h.getNewManagerAccountId()));
                if (!isFormerManager) {
                    throw new org.springframework.security.access.AccessDeniedException("Unauthorized to view management history");
                }
            }
        }

        List<com.apms.domain.profile.CompanyProfileManagerHistory> list = historyRepository.findByCompanyProfileIdOrderByTransferredAtDesc(profile.getId());
        return list.stream().map(h -> com.apms.domain.profile.dto.CompanyProfileManagerHistoryDto.builder()
                .id(h.getId())
                .companyProfileId(h.getCompanyProfileId())
                .companyId(h.getCompanyId())
                .previousManagerAccountId(h.getPreviousManagerAccountId())
                .previousManagerDisplayName(h.getPreviousManagerDisplayName())
                .newManagerAccountId(h.getNewManagerAccountId())
                .newManagerDisplayName(h.getNewManagerDisplayName())
                .transferredByAccountId(h.getTransferredByAccountId())
                .transferredByDisplayName(h.getTransferredByDisplayName())
                .reason(h.getReason())
                .transferredAt(h.getTransferredAt())
                .build()
        ).collect(java.util.stream.Collectors.toList());
    }

    public List<com.apms.domain.profile.dto.EligibleManagerDto> getEligibleManagers(String companyProfileId, UserDetailsImpl currentUser) {
        CompanyProfile profile = findProfileByProfileIdOrThrow(companyProfileId);

        boolean isAdmin = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
        boolean isOwner = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_OWNER"));
        boolean isResponsibleManager = profile.getResponsibleManagerId() != null && profile.getResponsibleManagerId().equals(currentUser.getId());

        if (!isAdmin && !isOwner && !isResponsibleManager) {
            throw new org.springframework.security.access.AccessDeniedException("You are not authorized to view eligible managers for this profile");
        }

        List<com.apms.domain.user.Account> managers = accountRepository.findActiveAccountsByRole(com.apms.common.enums.SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        return managers.stream()
                .filter(acc -> !Objects.equals(acc.getId(), profile.getResponsibleManagerId()))
                .map(acc -> com.apms.domain.profile.dto.EligibleManagerDto.builder()
                        .accountId(acc.getId())
                        .displayName(resolveDisplayName(acc.getId()))
                        .email(acc.getEmail())
                        .build())
                .sorted(Comparator.comparing(com.apms.domain.profile.dto.EligibleManagerDto::getDisplayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(java.util.stream.Collectors.toList());
    }

    private CompanyProfile findProfileByCompanyIdOrThrow(String companyId) {
        return profileRepository.findByCompanyId(companyId)
                .or(() -> profileRepository.findById(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("Company profile not found: " + companyId));
    }

    private String resolveRelationshipType(String companyId) {
        if (!StringUtils.hasText(companyId)) return null;

        List<String> targetIds = new ArrayList<>();
        targetIds.add(companyId.trim());

        java.util.Optional<CompanyProfile> profileOpt = profileRepository.findByCompanyId(companyId)
                .or(() -> profileRepository.findById(companyId));
        if (profileOpt.isPresent()) {
            CompanyProfile p = profileOpt.get();
            if (StringUtils.hasText(p.getCompanyId()) && !targetIds.contains(p.getCompanyId().trim())) {
                targetIds.add(p.getCompanyId().trim());
            }
            if (StringUtils.hasText(p.getId()) && !targetIds.contains(p.getId().trim())) {
                targetIds.add(p.getId().trim());
            }
        }

        try {
            String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
            if (targetIds.contains(ownerCompanyId)) return null; // It's the owner

            // Query canonical current Neo4j relationship between owner and target company
            String cypher = """
                MATCH (:Company {companyId: $ownerCompanyId})-[r:PARTNER_WITH|COMPETITOR_OF|POTENTIAL_PARTNER_OF|SUPPLIER_OF|CUSTOMER_OF]-(c:Company)
                WHERE c.companyId IN $targetIds
                RETURN type(r) AS relType
                ORDER BY coalesce(r.confirmedAt, datetime('1970-01-01T00:00:00Z')) DESC
                LIMIT 1
                """;

            java.util.List<String> types = new java.util.ArrayList<>(neo4jClient.query(cypher)
                    .bind(ownerCompanyId).to("ownerCompanyId")
                    .bind(targetIds).to("targetIds")
                    .fetchAs(String.class)
                    .mappedBy((typeSystem, record) -> record.get("relType").asString())
                    .all());

            if (types != null && !types.isEmpty() && StringUtils.hasText(types.get(0))) {
                return types.get(0);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve Neo4j relationship for companyId {}: {}", companyId, e.getMessage());
        }

        // Fallback: check linked projects for targetRelationshipType ONLY if project is COMPLETED
        // and genuinely no graph relationship exists. Never override current Neo4j relationship.
        try {
            if (profileOpt.isPresent() && profileOpt.get().getSourceRefs() != null && profileOpt.get().getSourceRefs().getProjectIds() != null) {
                for (String pid : profileOpt.get().getSourceRefs().getProjectIds()) {
                    try {
                        Long pId = Long.parseLong(pid);
                        java.util.Optional<Project> projOpt = projectRepository.findById(pId);
                        if (projOpt.isPresent() && projOpt.get().getStatus() == com.apms.common.enums.ProjectStatus.COMPLETED && projOpt.get().getTargetRelationshipType() != null) {
                            return projOpt.get().getTargetRelationshipType().name();
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private CompanyProfile.Identity mapIdentity(CompanyCandidate.Identity i) {
        if (i == null) return null;
        return CompanyProfile.Identity.builder()
                .legalName(i.getLegalName())
                .tradeName(i.getTradeName())
                .taxCode(i.getTaxCode())
                .registrationNumber(i.getRegistrationNumber())
                .build();
    }

    private CompanyProfile.Business mapBusiness(CompanyCandidate.Business b) {
        if (b == null) return null;
        return CompanyProfile.Business.builder()
                .industries(b.getIndustries())
                .businessModel(b.getBusinessModel())
                .foundedYear(b.getFoundedYear())
                .companyDescription(b.getCompanyDescription())
                .products(b.getProducts() != null ? mapProducts(b.getProducts()) : null)
                .markets(b.getMarkets())
                .targetCustomers(b.getTargetCustomers())
                .build();
    }

    private List<CompanyProfile.Product> mapProducts(List<CompanyCandidate.Product> candidateProducts) {
        if (candidateProducts == null) return null;
        Set<String> seen = new java.util.HashSet<>();
        return candidateProducts.stream()
                .filter(p -> p != null && StringUtils.hasText(p.getName()))
                .map(p -> p.getName().trim())
                .filter(name -> !name.isEmpty())
                .filter(name -> seen.add(name.toLowerCase(java.util.Locale.ROOT)))
                .map(name -> CompanyProfile.Product.builder().name(name).build())
                .toList();
    }

    private CompanyProfile.CompanySize mapCompanySize(CompanyCandidate.CompanySize s) {
        if (s == null) return null;
        return CompanyProfile.CompanySize.builder()
                .employeeCount(s.getEmployeeCount())
                .build();
    }

    private CompanyProfile.Contact mapContact(CompanyCandidate.Contact c) {
        if (c == null) return null;
        java.util.List<CompanyProfile.Address> profileAddresses = null;
        if (c.getAddresses() != null && !c.getAddresses().isEmpty()) {
            profileAddresses = c.getAddresses().stream()
                    .map(a -> CompanyProfile.Address.builder()
                            .type(a.getType())
                            .fullAddress(a.getFullAddress())
                            .city(a.getCity())
                            .country(a.getCountry())
                            .build())
                    .toList();
        } else if (c.getAddress() != null && !c.getAddress().trim().isEmpty()) {
            profileAddresses = java.util.List.of(
                    CompanyProfile.Address.builder()
                            .fullAddress(c.getAddress().trim())
                            .build()
            );
        }
        return CompanyProfile.Contact.builder()
                .website(c.getWebsite())
                .emails(c.getEmails())
                .phones(c.getPhones())
                .addresses(profileAddresses)
                .build();
    }

    private CompanyProfile.Insights mapInsights(CompanyCandidate.Insights i) {
        if (i == null) return null;
        return CompanyProfile.Insights.builder()
                .strengths(i.getStrengths())
                .weaknesses(i.getWeaknesses())
                .opportunities(i.getOpportunities())
                .threats(i.getThreats())
                .build();
    }

    public boolean checkDuplicateByTaxCode(String taxCode) {
        if (!StringUtils.hasText(taxCode)) {
            return false;
        }
        String normTax = taxCode.replaceAll("[\\s\\-]", "").trim();
        Optional<CompanyProfile> profileOpt = profileRepository.findByIdentityTaxCode(normTax);
        if (profileOpt.isEmpty()) {
            return false;
        }
        return companyProfileOfficialEvaluator.isOfficial(profileOpt.get());
    }

    public boolean isOfficialCompanyProfile(CompanyProfile profile) {
        return companyProfileOfficialEvaluator.isOfficial(profile);
    }
}
